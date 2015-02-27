/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import org.immutables.value.Value;

/**
 * Represents the command line options that should be passed to scalac. Note that the options do not
 * include either the classpath or the directory for storing class files.
 */
@Value.Immutable
@BuckStyleImmutable
public abstract class AbstractScalacOptions implements RuleKeyAppendable {

  protected abstract Optional<BuildTarget> getLibraryDep();

  protected abstract Optional<SourcePath> getScalacPath();
  protected abstract Optional<String> getBootclasspath();
  protected abstract ImmutableList<String> getExtraArguments();

  @Value.Default
  protected boolean isProductionBuild() {
    return false;
  }

  @Value.Default
  protected boolean isVerbose() {
    return false;
  }

  @Value.Default
  protected String getTargetLevel() {
    return "jvm-1.7";
  }

  protected boolean isDebug() {
    return !isProductionBuild();
  }

  @Value.Lazy
  public Scalac getScalac() {
    if (!getScalacPath().isPresent()) {
      throw new RuntimeException("Could not find scalac");
    }

    return new ExternalScalac(
        ImmutableList.of(getScalacPath().get()));
  }

  public void appendOptionsToList(
      ImmutableList.Builder<String> optionsBuilder) {

    // Add some standard options.
    optionsBuilder.add("-target:" + getTargetLevel());
    optionsBuilder.add("-nobootcp");

    if (isProductionBuild()) {
      optionsBuilder.add("-optimize");
    }

    if (isDebug()) {
      optionsBuilder.add("-g:vars");
    }

    if (isVerbose()) {
      optionsBuilder.add("-verbose");
      optionsBuilder.add("-uniqid");
      optionsBuilder.add("-explaintypes");
    }

    // Override the bootclasspath if Buck is building Scala code for Android.
    if (getBootclasspath().isPresent()) {
      optionsBuilder.add("-javabootclasspath", getBootclasspath().get());
    }

    // Add extra arguments.
    optionsBuilder.addAll(getExtraArguments());
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    // TODO(simons): Include bootclasspath params.
    builder
        .setReflectively("targetLevel", getTargetLevel())
        .setReflectively("extraArguments", Joiner.on(',').join(getExtraArguments()))
        .setReflectively("production", isProductionBuild())
        .setReflectively("debug", isDebug())
        .setReflectively("scalac", getScalac());

    return builder;
  }

  public ImmutableSortedSet<SourcePath> getInputs(SourcePathResolver resolver) {
    ImmutableSortedSet.Builder<SourcePath> builder = ImmutableSortedSet.<SourcePath>naturalOrder();

    Optional<SourcePath> scalacJarPath = getScalacPath();
    if (scalacJarPath.isPresent()) {
      SourcePath sourcePath = scalacJarPath.get();

      // Add the original rule regardless of what happens next.
      builder.add(sourcePath);

      Optional<BuildRule> possibleRule = resolver.getRule(sourcePath);

      if (possibleRule.isPresent()) {
        BuildRule rule = possibleRule.get();

        // And now include any transitive deps that contribute to the classpath.
        if (rule instanceof JavaLibrary) {
          builder.addAll(
              FluentIterable.from(((JavaLibrary) rule).getDepsForTransitiveClasspathEntries())
                  .transform(SourcePaths.getToBuildTargetSourcePath())
                  .toList());
        } else {
          builder.add(sourcePath);
        }
      }
    }

    return builder.build();
  }

  public static ScalacOptions.Builder builder() {
    return ScalacOptions.builder();
  }

  public static ScalacOptions.Builder builder(ScalacOptions options) {
    Preconditions.checkNotNull(options);

    ScalacOptions.Builder builder = ScalacOptions.builder();

    builder.setVerbose(options.isVerbose());
    builder.setProductionBuild(options.isProductionBuild());

    builder.setLibraryDep(options.getLibraryDep());
    builder.setScalacPath(options.getScalacPath());
    builder.setBootclasspath(options.getBootclasspath());
    builder.setTargetLevel(options.getTargetLevel());
    builder.addAllExtraArguments(options.getExtraArguments());

    return builder;
  }
}
