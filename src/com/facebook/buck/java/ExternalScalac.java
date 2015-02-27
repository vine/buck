/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.java;


import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.zip.Unzip;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

public class ExternalScalac implements Scalac {
  private static final ScalacVersion DEFAULT_VERSION = ScalacVersion.of("unknown");

  private final ImmutableList<SourcePath> classpath;

  public ExternalScalac(Iterable<SourcePath> classpath) {
    this.classpath = ImmutableList.copyOf(classpath);
  }

  static ProcessExecutor createProcessExecutor(PrintStream stdout, PrintStream stderr) {
    return new ProcessExecutor(
              new Console(
                  Verbosity.SILENT,
                  stdout,
                  stderr,
                  Ansi.withoutTty()));
  }

  private ImmutableList<String> getScalacCommand(final SourcePathResolver resolver) {
    String classpathStr = FluentIterable
        .from(classpath)
        .skip(1)
        .transform(new Function<SourcePath, String>() {
            @Override
            public String apply(SourcePath path) {
              return resolver.getPath(path).toString();
            }
        })
        .join(Joiner.on(":"));

    return ImmutableList.of(
        "java", "-Dscala.usejavacp=true",
        "-classpath", classpathStr,
        "-jar", resolver.getPath(classpath.get(0)).toString());
  }

  @Override
  public synchronized ScalacVersion getVersion() {
    return DEFAULT_VERSION;
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathResolver resolver) {
    return resolver.filterBuildRuleInputs(getInputs());
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return classpath;
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    return getScalacCommand(resolver);
  }

  @Override
  public String getDescription(
      ImmutableList<String> options,
      ImmutableSet<Path> scalaSourceFilePaths,
      Optional<Path> pathToSrcsList) {
    StringBuilder builder = new StringBuilder();
    builder.append(getShortName());
    builder.append(" ");
    Joiner.on(" ").appendTo(builder, options);
    builder.append(" ");

    if (pathToSrcsList.isPresent()) {
      builder.append("@").append(pathToSrcsList.get());
    } else {
      Joiner.on(" ").appendTo(builder, scalaSourceFilePaths);
    }

    return builder.toString();
  }

  @Override
  public String getShortName() {
    return FluentIterable
            .from(classpath)
            .transform(new Function<SourcePath, String>() {
                @Override
                public String apply(SourcePath path) {
                  if (path instanceof BuildTargetSourcePath) {
                    // This thing has an ugly toString.
                    return ((BuildTargetSourcePath) path).getTarget().toString();
                  } else {
                    return path.toString();
                  }
                }
              })
            .join(Joiner.on(":"));
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    return builder.setReflectively("scalac.classpath", classpath);
  }

  @Override
  public int buildWithClasspath(
      ExecutionContext context,
      ProjectFilesystem filesystem,
      SourcePathResolver resolver,
      BuildTarget invokingRule,
      ImmutableList<String> options,
      ImmutableSet<Path> scalaSourceFilePaths,
      Optional<Path> pathToSrcsList,
      Optional<Path> workingDirectory) throws InterruptedException {
    ImmutableList<String> scalacCmd = getScalacCommand(resolver);
    ImmutableList.Builder<String> command = ImmutableList.builder();
    command.addAll(scalacCmd);
    command.addAll(options);

    ImmutableList<Path> expandedSources;
    try {
      expandedSources = getExpandedSourcePaths(
          filesystem,
          invokingRule,
          scalaSourceFilePaths,
          workingDirectory);
    } catch (IOException e) {
      throw new HumanReadableException(
          "Unable to expand sources for %s into %s",
          invokingRule,
          workingDirectory);
    }
    if (pathToSrcsList.isPresent()) {
      try {
        filesystem.writeLinesToPath(
            FluentIterable.from(expandedSources)
                .transform(Functions.toStringFunction())
                .transform(ARGFILES_ESCAPER),
            pathToSrcsList.get());
        command.add("@" + pathToSrcsList.get());
      } catch (IOException e) {
        context.logError(
            e,
            "Cannot write list of .scala files to compile to %s file! Terminating compilation.",
            pathToSrcsList.get());
        return 1;
      }
    } else {
      for (Path source : expandedSources) {
        command.add(source.toString());
      }
    }

    ProcessBuilder processBuilder = new ProcessBuilder(command.build());

    // Set environment to client environment and add additional information.
    Map<String, String> env = processBuilder.environment();
    env.clear();
    env.putAll(context.getEnvironment());
    env.put("BUCK_INVOKING_RULE", invokingRule.toString());
    env.put("BUCK_TARGET", invokingRule.toString());
    env.put("BUCK_DIRECTORY_ROOT", filesystem.getRootPath().toAbsolutePath().toString());

    processBuilder.directory(filesystem.getRootPath().toAbsolutePath().toFile());
    // Run the command
    int exitCode = -1;
    try {
      ProcessExecutor.Result result = context.getProcessExecutor().execute(processBuilder.start());
      exitCode = result.getExitCode();
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return exitCode;
    }

    return exitCode;
  }

  private ImmutableList<Path> getExpandedSourcePaths(
      ProjectFilesystem projectFilesystem,
      BuildTarget invokingRule,
      ImmutableSet<Path> scalaSourceFilePaths,
      Optional<Path> workingDirectory) throws IOException {

    // Add sources file or sources list to command
    ImmutableList.Builder<Path> sources = ImmutableList.builder();
    for (Path path : scalaSourceFilePaths) {
      if (path.toString().endsWith(".scala")) {
        sources.add(path);
      } else if (path.toString().endsWith(SRC_ZIP)) {
        if (!workingDirectory.isPresent()) {
          throw new HumanReadableException(
              "Attempting to compile target %s which specified a .src.zip input %s but no " +
                  "working directory was specified.",
              invokingRule.toString(),
              path);
        }
        // For a Zip of .scala files, create a JavaFileObject for each .scala entry.
        ImmutableList<Path> zipPaths = Unzip.extractZipFile(
            projectFilesystem.resolve(path),
            projectFilesystem.resolve(workingDirectory.get()),
            Unzip.ExistingFileMode.OVERWRITE);
        sources.addAll(
            FluentIterable.from(zipPaths)
                .filter(
                    new Predicate<Path>() {
                      @Override
                      public boolean apply(Path input) {
                        return input.toString().endsWith(".scala");
                      }
                    }));
      }
    }
    return sources.build();
  }
}
