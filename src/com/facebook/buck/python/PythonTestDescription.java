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

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.HasSourceUnderTest;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PythonTestDescription implements
    Description<PythonTestDescription.Arg>,
    ImplicitDepsInferringDescription<PythonTestDescription.Arg> {

  private static final BuildRuleType TYPE = BuildRuleType.of("python_test");

  private static final Flavor BINARY_FLAVOR = ImmutableFlavor.of("binary");

  private static final MacroHandler MACRO_HANDLER =
      new MacroHandler(
          ImmutableMap.<String, MacroExpander>of(
              "location", new LocationMacroExpander()));

  private final PythonBinaryDescription binaryDescription;
  private final PythonBuckConfig pythonBuckConfig;
  private final FlavorDomain<PythonPlatform> pythonPlatforms;
  private final CxxPlatform defaultCxxPlatform;
  private final Optional<Long> defaultTestRuleTimeoutMs;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public PythonTestDescription(
      PythonBinaryDescription binaryDescription,
      PythonBuckConfig pythonBuckConfig,
      FlavorDomain<PythonPlatform> pythonPlatforms,
      CxxPlatform defaultCxxPlatform,
      Optional<Long> defaultTestRuleTimeoutMs,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.binaryDescription = binaryDescription;
    this.pythonBuckConfig = pythonBuckConfig;
    this.pythonPlatforms = pythonPlatforms;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
    this.cxxPlatforms = cxxPlatforms;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @VisibleForTesting
  protected static Path getTestMainName() {
    return Paths.get("__test_main__.py");
  }

  @VisibleForTesting
  protected static Path getTestModulesListName() {
    return Paths.get("__test_modules__.py");
  }

  @VisibleForTesting
  protected static Path getTestModulesListPath(BuildTarget buildTarget) {
    return BuildTargets.getGenPath(buildTarget, "%s").resolve(getTestModulesListName());
  }

  @VisibleForTesting
  protected static BuildTarget getBinaryBuildTarget(BuildTarget target) {
    return BuildTargets.createFlavoredBuildTarget(target.checkUnflavored(), BINARY_FLAVOR);
  }

  /**
   * Create the contents of a python source file that just contains a list of
   * the given test modules.
   */
  private static String getTestModulesListContents(ImmutableSet<String> modules) {
    String contents = "TEST_MODULES = [\n";
    for (String module : modules) {
      contents += String.format("    \"%s\",\n", module);
    }
    contents += "]";
    return contents;
  }

  /**
   * Return a {@link BuildRule} that constructs the source file which contains the list
   * of test modules this python test rule will run.  Setting up a separate build rule
   * for this allows us to use the existing python binary rule without changes to account
   * for the build-time creation of this file.
   */
  private static BuildRule createTestModulesSourceBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      Path outputPath,
      ImmutableSet<String> testModules) {

    // Modify the build rule params to change the target, type, and remove all deps.
    BuildRuleParams newParams = params.copyWithChanges(
        BuildTargets.createFlavoredBuildTarget(
            params.getBuildTarget().checkUnflavored(),
            ImmutableFlavor.of("test_module")),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));

    String contents = getTestModulesListContents(testModules);

    return new WriteFile(
        newParams,
        new SourcePathResolver(resolver),
        contents,
        outputPath,
        /* executable */ false);
  }

  @Override
  public <A extends Arg> PythonTest createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      final A args) throws NoSuchBuildTargetException {

    PythonPlatform pythonPlatform = pythonPlatforms
        .getValue(params.getBuildTarget())
        .or(pythonPlatforms.getValue(
            args.platform
                .transform(Flavor.TO_FLAVOR)
                .or(pythonPlatforms.getFlavors().iterator().next())));
    CxxPlatform cxxPlatform = cxxPlatforms.getValue(params.getBuildTarget()).or(defaultCxxPlatform);
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    Path baseModule = PythonUtil.getBasePath(params.getBuildTarget(), args.baseModule);

    ImmutableMap<Path, SourcePath> srcs =
        ImmutableMap.<Path, SourcePath>builder()
            .putAll(
                PythonUtil.toModuleMap(
                    params.getBuildTarget(),
                    pathResolver,
                    "srcs",
                    baseModule,
                    args.srcs.asSet()))
            .putAll(
                PythonUtil.toModuleMap(
                    params.getBuildTarget(),
                    pathResolver,
                    "platformSrcs",
                    baseModule,
                    args.platformSrcs.get()
                        .getMatchingValues(pythonPlatform.getFlavor().toString())))
            .build();

    ImmutableMap<Path, SourcePath> resources =
        ImmutableMap.<Path, SourcePath>builder()
            .putAll(
                PythonUtil.toModuleMap(
                    params.getBuildTarget(),
                    pathResolver,
                    "resources",
                    baseModule,
                    args.resources.asSet()))
            .putAll(
                PythonUtil.toModuleMap(
                    params.getBuildTarget(),
                    pathResolver,
                    "platformResources",
                    baseModule,
                    args.platformResources.get()
                        .getMatchingValues(pythonPlatform.getFlavor().toString())))
            .build();

    // Convert the passed in module paths into test module names.
    ImmutableSet.Builder<String> testModulesBuilder = ImmutableSet.builder();
    for (Path name : srcs.keySet()) {
      testModulesBuilder.add(
          PythonUtil.toModuleName(params.getBuildTarget(), name.toString()));
    }
    ImmutableSet<String> testModules = testModulesBuilder.build();

    // Construct a build rule to generate the test modules list source file and
    // add it to the build.
    BuildRule testModulesBuildRule = createTestModulesSourceBuildRule(
        params,
        resolver,
        getTestModulesListPath(params.getBuildTarget()),
        testModules);
    resolver.addToIndex(testModulesBuildRule);

    // Build up the list of everything going into the python test.
    PythonPackageComponents testComponents = PythonPackageComponents.of(
        ImmutableMap
            .<Path, SourcePath>builder()
            .put(
                getTestModulesListName(),
                new BuildTargetSourcePath(testModulesBuildRule.getBuildTarget()))
            .put(
                getTestMainName(),
                pythonBuckConfig.getPathToTestMain(params.getProjectFilesystem()))
            .putAll(srcs)
            .build(),
        resources,
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableSet.<SourcePath>of(),
        args.zipSafe);
    PythonPackageComponents allComponents =
        PythonUtil.getAllComponents(
            params,
            resolver,
            pathResolver,
            testComponents,
            pythonPlatform,
            cxxPlatform,
            pythonBuckConfig.getNativeLinkStrategy());

    // Build the PEX using a python binary rule with the minimum dependencies.
    BuildRuleParams binaryParams = params.copyWithChanges(
        getBinaryBuildTarget(params.getBuildTarget()),
        Suppliers.ofInstance(PythonUtil.getDepsFromComponents(pathResolver, allComponents)),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));
    PythonBinary binary =
        binaryDescription.createPackageRule(
            binaryParams,
            resolver,
            pathResolver,
            pythonPlatform,
            cxxPlatform,
            PythonUtil.toModuleName(params.getBuildTarget(), getTestMainName().toString()),
            allComponents,
            args.buildArgs.or(ImmutableList.<String>of()));
    resolver.addToIndex(binary);

    // Supplier which expands macros in the passed in test environment.
    Supplier<ImmutableMap<String, String>> testEnv =
        new Supplier<ImmutableMap<String, String>>() {
          @Override
          public ImmutableMap<String, String> get() {
            return ImmutableMap.copyOf(
                Maps.transformValues(
                    args.env.or(ImmutableMap.<String, String>of()),
                    MACRO_HANDLER.getExpander(
                        params.getBuildTarget(),
                        params.getCellRoots(),
                        resolver)));
          }
        };

    // Generate and return the python test rule, which depends on the python binary rule above.
    return new PythonTest(
        params.copyWithDeps(
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(params.getDeclaredDeps().get())
                    .add(binary)
                    .build()),
            params.getExtraDeps()),
        pathResolver,
        testEnv,
        binary,
        ImmutableSortedSet.copyOf(Sets.difference(params.getDeps(), binaryParams.getDeps())),
        resolver.getAllRules(args.sourceUnderTest.or(ImmutableSortedSet.<BuildTarget>of())),
        args.labels.or(ImmutableSet.<Label>of()),
        args.testRuleTimeoutMs.or(defaultTestRuleTimeoutMs),
        args.contacts.or(ImmutableSet.<String>of()));
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      Function<Optional<String>, Path> cellRoots,
      Arg constructorArg) {
    ImmutableList.Builder<BuildTarget> targets = ImmutableList.builder();

    if (pythonBuckConfig.getPackageStyle() != PythonBuckConfig.PackageStyle.INPLACE) {
      targets.addAll(pythonBuckConfig.getPexTarget().asSet());
    }

    return targets.build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends PythonLibraryDescription.Arg implements HasSourceUnderTest {
    public Optional<ImmutableSet<String>> contacts;
    public Optional<ImmutableSet<Label>> labels;
    public Optional<ImmutableSortedSet<BuildTarget>> sourceUnderTest;
    public Optional<String> platform;

    public Optional<ImmutableList<String>> buildArgs;

    public Optional<ImmutableMap<String, String>> env;
    public Optional<Long> testRuleTimeoutMs;

    @Override
    public ImmutableSortedSet<BuildTarget> getSourceUnderTest() {
      return sourceUnderTest.get();
    }
  }

}
