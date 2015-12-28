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
import com.facebook.buck.cxx.WindowsLinker;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public class PythonBinaryDescription implements
    Description<PythonBinaryDescription.Arg>,
    ImplicitDepsInferringDescription<PythonBinaryDescription.Arg> {

  private static final Logger LOG = Logger.get(PythonBinaryDescription.class);

  public static final BuildRuleType TYPE = BuildRuleType.of("python_binary");

  private final PythonBuckConfig pythonBuckConfig;
  private final FlavorDomain<PythonPlatform> pythonPlatforms;
  private final CxxPlatform defaultCxxPlatform;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public PythonBinaryDescription(
      PythonBuckConfig pythonBuckConfig,
      FlavorDomain<PythonPlatform> pythonPlatforms,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.pythonBuckConfig = pythonBuckConfig;
    this.pythonPlatforms = pythonPlatforms;
    this.defaultCxxPlatform = defaultCxxPlatform;
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

  private PythonPackageComponents addMissingInitModules(
      PythonPackageComponents components,
      SourcePath emptyInit) {

    Map<Path, SourcePath> initModules = Maps.newLinkedHashMap();

    // Insert missing `__init__.py` modules.
    Set<Path> packages = Sets.newHashSet();
    for (Path module : components.getModules().keySet()) {
      Path pkg = module;
      while ((pkg = pkg.getParent()) != null && !packages.contains(pkg)) {
        Path init = pkg.resolve("__init__.py");
        if (!components.getModules().containsKey(init)) {
          initModules.put(init, emptyInit);
        }
        packages.add(pkg);
      }
    }

    return components.withModules(
        ImmutableMap.<Path, SourcePath>builder()
            .putAll(components.getModules())
            .putAll(initModules)
            .build());
  }

  private PythonInPlaceBinary createInPlaceBinaryRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      String mainModule,
      PythonPackageComponents components) {

    // We don't currently support targeting Windows.
    if (cxxPlatform.getLd() instanceof WindowsLinker) {
      throw new HumanReadableException(
          "%s: cannot build in-place python binaries for Windows (%s)",
          params.getBuildTarget(),
          cxxPlatform.getFlavor());
    }

    // Generate an empty __init__.py module to fill in for any missing ones in the link tree.
    BuildTarget emptyInitTarget =
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(ImmutableFlavor.of("__init__"))
            .build();
    Path emptyInitPath =
        BuildTargets.getGenPath(
            params.getBuildTarget(),
            "%s/__init__.py");
    resolver.addToIndex(
        new WriteFile(
            params.copyWithChanges(
                emptyInitTarget,
                Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
                Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
            pathResolver,
            "",
            emptyInitPath,
            /* executable */ false));

    // Add in any missing init modules into the python components.
    components = addMissingInitModules(components, new BuildTargetSourcePath(emptyInitTarget));

    BuildTarget linkTreeTarget =
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(ImmutableFlavor.of("link-tree"))
            .build();
    Path linkTreeRoot = params.getProjectFilesystem().resolve(
        BuildTargets.getGenPath(linkTreeTarget, "%s"));
    SymlinkTree linkTree;
    try {
      linkTree =
        resolver.addToIndex(
            new SymlinkTree(
                params.copyWithChanges(
                    linkTreeTarget,
                    Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
                    Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
                pathResolver,
                linkTreeRoot,
                ImmutableMap.<Path, SourcePath>builder()
                    .putAll(components.getModules())
                    .putAll(components.getResources())
                    .putAll(components.getNativeLibraries())
                    .build()));
    } catch (SymlinkTree.InvalidSymlinkTreeException e) {
      throw e.getHumanReadableExceptionForBuildTarget(
          params.getBuildTarget().getUnflavoredBuildTarget());
    }

    return new PythonInPlaceBinary(
        params,
        pathResolver,
        pythonPlatform,
        cxxPlatform,
        linkTree,
        mainModule,
        components,
        pythonPlatform.getEnvironment(),
        pythonBuckConfig.getPexExtension());
  }

  protected PythonBinary createPackageRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      String mainModule,
      PythonPackageComponents components,
      ImmutableList<String> buildArgs) {

    switch (pythonBuckConfig.getPackageStyle()) {

      case INPLACE:
        return createInPlaceBinaryRule(
            params,
            resolver,
            pathResolver,
            pythonPlatform,
            cxxPlatform,
            mainModule,
            components);

      case PEX_INPLACE:
      case STANDALONE:
        ImmutableSortedSet<BuildRule> componentDeps =
            PythonUtil.getDepsFromComponents(pathResolver, components);
        Tool pexTool = pythonBuckConfig.getPexTool(resolver);
        PexStep.PexStyle pexStyle = pythonBuckConfig.getPackageStyle() == PythonBuckConfig.PackageStyle.PEX_INPLACE ?
            PexStep.PexStyle.DIRECTORY : PexStep.PexStyle.FILE;
        return new PythonPexBinary(
            params.copyWithDeps(
                Suppliers.ofInstance(
                    ImmutableSortedSet.<BuildRule>naturalOrder()
                        .addAll(componentDeps)
                        .addAll(pexTool.getDeps(pathResolver))
                        .build()),
                Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
            pathResolver,
            pythonPlatform,
            pexTool,
            buildArgs,
            pythonBuckConfig.getPathToPexExecuter(resolver).or(pythonPlatform.getEnvironment()),
            pythonBuckConfig.getPexExtension(),
            pexStyle,
            pythonPlatform.getEnvironment(),
            mainModule,
            components,
            // Attach any additional declared deps that don't qualify as build time deps,
            // as runtime deps, so that we make to include other things we depend on in
            // the build.
            ImmutableSortedSet.copyOf(
                Sets.difference(params.getDeclaredDeps().get(), componentDeps)));

      default:
        throw new IllegalStateException();

    }

  }

  @Override
  public <A extends Arg> PythonBinary createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    if (!(args.main.isPresent() ^ args.mainModule.isPresent())) {
      throw new HumanReadableException(
          "%s: must set exactly one of `main_module` and `main`",
          params.getBuildTarget());
    }
    Path baseModule = PythonUtil.getBasePath(params.getBuildTarget(), args.baseModule);

    String mainModule;
    ImmutableMap.Builder<Path, SourcePath> modules = ImmutableMap.builder();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    // If `main` is set, add it to the map of modules for this binary and also set it as the
    // `mainModule`, otherwise, use the explicitly set main module.
    if (args.main.isPresent()) {
      LOG.warn(
          "%s: parameter `main` is deprecated, please use `main_module` instead.",
          params.getBuildTarget());
      String mainName = pathResolver.getSourcePathName(params.getBuildTarget(), args.main.get());
      Path main = baseModule.resolve(mainName);
      modules.put(baseModule.resolve(mainName), args.main.get());
      mainModule = PythonUtil.toModuleName(params.getBuildTarget(), main.toString());
    } else {
      mainModule = args.mainModule.get();
    }
    // Build up the list of all components going into the python binary.
    PythonPackageComponents binaryPackageComponents = PythonPackageComponents.of(
        modules.build(),
        /* resources */ ImmutableMap.<Path, SourcePath>of(),
        /* nativeLibraries */ ImmutableMap.<Path, SourcePath>of(),
        /* prebuiltLibraries */ ImmutableSet.<SourcePath>of(),
        /* zipSafe */ args.zipSafe);
    // Extract the platforms from the flavor, falling back to the default platforms if none are
    // found.
    PythonPlatform pythonPlatform = pythonPlatforms
        .getValue(params.getBuildTarget())
        .or(pythonPlatforms.getValue(
            args.platform
                .transform(Flavor.TO_FLAVOR)
                .or(pythonPlatforms.getFlavors().iterator().next())));
    CxxPlatform cxxPlatform = cxxPlatforms.getValue(params.getBuildTarget()).or(defaultCxxPlatform);
    PythonPackageComponents allPackageComponents =
        PythonUtil.getAllComponents(
            params,
            resolver,
            pathResolver,
            binaryPackageComponents,
            pythonPlatform,
            cxxPlatform,
            pythonBuckConfig.getNativeLinkStrategy());
    return createPackageRule(
        params,
        resolver,
        pathResolver,
        pythonPlatform,
        cxxPlatform,
        mainModule,
        allPackageComponents,
        args.buildArgs.or(ImmutableList.<String>of()));
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
  public static class Arg {
    public Optional<SourcePath> main;
    public Optional<String> mainModule;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
    public Optional<String> baseModule;
    public Optional<Boolean> zipSafe;
    public Optional<ImmutableList<String>> buildArgs;
    public Optional<String> platform;
  }

}
