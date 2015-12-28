/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.python;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cxx.VersionedTool;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuckVersion;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.PackagedResource;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;

import javax.annotation.Nonnull;

public class PythonBuckConfig {

  public static final Flavor DEFAULT_PYTHON_PLATFORM = ImmutableFlavor.of("py-default");

  private static final String SECTION = "python";
  private static final String PYTHON_PLATFORM_SECTION_PREFIX = "python#";

  // Prefer "python2" where available (Linux), but fall back to "python" (Mac).
  private static final ImmutableList<String> PYTHON_INTERPRETER_NAMES =
      ImmutableList.of("python2", "python");

  private static final Path DEFAULT_PATH_TO_PEX =
      Paths.get(
          System.getProperty(
              "buck.path_to_pex",
              "src/com/facebook/buck/python/make_pex.py"))
          .toAbsolutePath();

  private static final LoadingCache<ProjectFilesystem, PathSourcePath> PATH_TO_TEST_MAIN =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<ProjectFilesystem, PathSourcePath>() {
                @Override
                public PathSourcePath load(@Nonnull ProjectFilesystem filesystem) {
                  return new PathSourcePath(
                      filesystem,
                      PythonBuckConfig.class + "/__test_main__.py",
                      new PackagedResource(filesystem, PythonBuckConfig.class, "__test_main__.py"));
                }
              });

  private final BuckConfig delegate;
  private final ExecutableFinder exeFinder;

  public PythonBuckConfig(BuckConfig config, ExecutableFinder exeFinder) {
    this.delegate = config;
    this.exeFinder = exeFinder;
  }

  @VisibleForTesting
  protected PythonPlatform getDefaultPythonPlatform(ProcessExecutor executor)
      throws InterruptedException {
    return getPythonPlatform(
        executor,
        DEFAULT_PYTHON_PLATFORM,
        delegate.getValue(SECTION, "interpreter"),
        delegate.getBuildTarget(SECTION, "library"));
  }

  /**
   * Constructs set of Python platform flavors given in a .buckconfig file, as is specified by
   * section names of the form python#{flavor name}.
   */
  public ImmutableList<PythonPlatform> getPythonPlatforms(
      ProcessExecutor processExecutor)
      throws InterruptedException {
    ImmutableList.Builder<PythonPlatform> builder = ImmutableList.builder();

    // Add the python platform described in the top-level section first.
    builder.add(getDefaultPythonPlatform(processExecutor));

    // Then add all additional python platform described in the extended sections.
    for (String section : delegate.getSections()) {
      if (section.startsWith(PYTHON_PLATFORM_SECTION_PREFIX)) {
        builder.add(
            getPythonPlatform(
                processExecutor,
                ImmutableFlavor.of(section.substring(PYTHON_PLATFORM_SECTION_PREFIX.length())),
                delegate.getValue(section, "interpreter"),
                delegate.getBuildTarget(section, "library")));
      }
    }

    return builder.build();
  }

  private PythonPlatform getPythonPlatform(
      ProcessExecutor processExecutor,
      Flavor flavor,
      Optional<String> interpreter,
      Optional<BuildTarget> library)
      throws InterruptedException {
    return PythonPlatform.of(
        flavor,
        getPythonEnvironment(processExecutor, interpreter),
        library);
  }

  /**
   * @return true if file is executable and not a directory.
   */
  private boolean isExecutableFile(File file) {
    return file.canExecute() && !file.isDirectory();
  }

  /**
   * Returns the path to python interpreter. If python is specified in 'interpreter' key
   * of the 'python' section that is used and an error reported if invalid.
   * @return The found python interpreter.
   */
  public String getPythonInterpreter(Optional<String> configPath) {
    ImmutableList<String> pythonInterpreterNames = PYTHON_INTERPRETER_NAMES;
    if (configPath.isPresent()) {
      // Python path in config. Use it or report error if invalid.
      File python = new File(configPath.get());
      if (isExecutableFile(python)) {
        return python.getAbsolutePath();
      }
      if (python.isAbsolute()) {
        throw new HumanReadableException("Not a python executable: " + configPath.get());
      }
      pythonInterpreterNames = ImmutableList.of(configPath.get());
    }

    for (String interpreterName : pythonInterpreterNames) {
      Optional<Path> python = exeFinder.getOptionalExecutable(
          Paths.get(interpreterName),
          delegate.getEnvironment());
      if (python.isPresent()) {
        return python.get().toAbsolutePath().toString();
      }
    }

    if (configPath.isPresent()) {
      throw new HumanReadableException("Not a python executable: " + configPath.get());
    } else {
      throw new HumanReadableException("No python2 or python found.");
    }
  }

  public String getPythonInterpreter() {
    Optional<String> configPath = delegate.getValue(SECTION, "interpreter");
    return getPythonInterpreter(configPath);
  }

  public PythonEnvironment getPythonEnvironment(
      ProcessExecutor processExecutor,
      Optional<String> configPath)
      throws InterruptedException {
    Path pythonPath = Paths.get(getPythonInterpreter(configPath));
    PythonVersion pythonVersion = getPythonVersion(processExecutor, pythonPath);
    return new PythonEnvironment(pythonPath, pythonVersion);
  }

  public PythonEnvironment getPythonEnvironment(ProcessExecutor processExecutor)
      throws InterruptedException {
    Optional<String> configPath = delegate.getValue(SECTION, "interpreter");
    return getPythonEnvironment(processExecutor, configPath);
  }

  public SourcePath getPathToTestMain(ProjectFilesystem filesystem) {
    return PATH_TO_TEST_MAIN.getUnchecked(filesystem);
  }

  public Optional<BuildTarget> getPexTarget() {
    return delegate.getMaybeBuildTarget(SECTION, "path_to_pex");
  }

  public Tool getPexTool(BuildRuleResolver resolver) {
    Optional<Tool> executable = delegate.getTool(SECTION, "path_to_pex", resolver);
    if (executable.isPresent()) {
      return executable.get();
    }
    return new VersionedTool(
        Paths.get(getPythonInterpreter()),
        ImmutableList.of("-S", DEFAULT_PATH_TO_PEX.toString()),
        "pex",
        BuckVersion.getVersion());
  }

  public Optional<Tool> getPathToPexExecuter(BuildRuleResolver resolver) {
    return delegate.getTool(SECTION, "path_to_pex_executer", resolver);
  }

  public NativeLinkStrategy getNativeLinkStrategy() {
    return delegate.getEnum(SECTION, "native_link_strategy", NativeLinkStrategy.class)
        .or(NativeLinkStrategy.SPEARATE);
  }

  public String getPexExtension() {
    return delegate.getValue(SECTION, "pex_extension").or(".pex");
  }

  private static PythonVersion getPythonVersion(ProcessExecutor processExecutor, Path pythonPath)
      throws InterruptedException {
    try {
      // Taken from pex's interpreter.py.
      String versionId = "import sys\n" +
          "\n" +
          "if hasattr(sys, 'pypy_version_info'):\n" +
          "  subversion = 'PyPy'\n" +
          "elif sys.platform.startswith('java'):\n" +
          "  subversion = 'Jython'\n" +
          "else:\n" +
          "  subversion = 'CPython'\n" +
          "\n" +
          "print('%s %s %s %s' % (subversion, sys.version_info[0], sys.version_info[1], sys.version_info[2]))\n";

      ProcessExecutor.Result versionResult = processExecutor.launchAndExecute(
          ProcessExecutorParams.builder().addCommand(pythonPath.toString(), "-").build(),
          EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT),
          Optional.of(versionId),
          /* timeOutMs */ Optional.<Long>absent(),
          /* timeoutHandler */ Optional.<Function<Process, Void>>absent());
      return extractPythonVersion(pythonPath, versionResult);
    } catch (IOException e) {
      throw new HumanReadableException(
          e,
          "Could not run \"%s --version\": %s",
          pythonPath,
          e.getMessage());
    }
  }

  @VisibleForTesting
  static PythonVersion extractPythonVersion(
      Path pythonPath,
      ProcessExecutor.Result versionResult) {
    if (versionResult.getExitCode() == 0) {
      // The first line is the compatibility version; the rest is interpreter-specific.
      // The compatibilty version should match PythonIdentity.from_id_string.
      String versionString = CharMatcher.WHITESPACE.trimFrom(
          CharMatcher.WHITESPACE.trimFrom(versionResult.getStderr().get()) +
              CharMatcher.WHITESPACE.trimFrom(versionResult.getStdout().get())
                  .replaceAll("\u001B\\[[;\\d]*m", ""));
      String[] versionLines = versionString.split("\\r?\\n");

      String[] compatibilityVersion = versionLines[0].split(" ");
      if (compatibilityVersion.length != 4) {
        throw new HumanReadableException(
            "`%s -` returned an invalid version string %s",
            pythonPath,
            versionString);
      }

      return PythonVersion.of(
          compatibilityVersion[0],
          compatibilityVersion[1] + "." + compatibilityVersion[2] + "." + compatibilityVersion[3]);
    } else {
      throw new HumanReadableException(versionResult.getStderr().get());
    }
  }

  public PackageStyle getPackageStyle() {
    return delegate.getEnum(SECTION, "package_style", PackageStyle.class)
        .or(PackageStyle.STANDALONE);
  }

  public enum PackageStyle {
    STANDALONE,
    INPLACE,
    PEX_INPLACE,
  }

}
