/*
 * Copyright 2015-present Facebook, Inc.
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.Config;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.DefaultCxxPlatforms;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.ParameterizedTests;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.martiansoftware.nailgun.NGContext;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

@RunWith(Parameterized.class)
public class PythonBinaryIntegrationTest {

  @Parameterized.Parameters(name = "{0},{1}")
  public static Collection<Object[]> data() {
    return ParameterizedTests.getPermutations(
        Arrays.asList(PythonBuckConfig.PackageStyle.values()),
        Arrays.asList(NativeLinkStrategy.values()));
  }

  @Parameterized.Parameter
  public PythonBuckConfig.PackageStyle packageStyle;

  @Parameterized.Parameter(value = 1)
  public NativeLinkStrategy nativeLinkStrategy;

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  public ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "python_binary", tmp);
    workspace.setUp();
    workspace.writeContentsToPath(
        "[python]\n" +
            "  package_style = " + packageStyle.toString().toLowerCase() + "\n" +
            "  native_link_strategy = " + nativeLinkStrategy.toString().toLowerCase() + "\n",
        ".buckconfig");
    PythonBuckConfig config = getPythonBuckConfig();
    assertThat(config.getPackageStyle(), equalTo(packageStyle));
    assertThat(config.getNativeLinkStrategy(), equalTo(nativeLinkStrategy));
  }

  @Test
  public void nonComponentDepsArePreserved() throws IOException {
    workspace.runBuckBuild("//:bin-with-extra-dep").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:extra");
  }

  @Test
  public void executionThroughSymlink() throws IOException, InterruptedException {
    assumeThat(Platform.detect(), Matchers.oneOf(Platform.MACOS, Platform.LINUX));
    workspace.runBuckBuild("//:bin").assertSuccess();
    String output = workspace.runBuckCommand("targets", "--show-output", "//:bin")
        .assertSuccess()
        .getStdout()
        .trim();
    Path link = workspace.getPath("link");
    Files.createSymbolicLink(
        link,
        workspace.getPath(Splitter.on(" ").splitToList(output).get(1)).toAbsolutePath());
    ProcessExecutor.Result result = workspace.runCommand(
        getPythonBuckConfig().getPythonInterpreter(), link.toString());
    assertThat(
        result.getStdout().or("") + result.getStderr().or(""),
        result.getExitCode(),
        equalTo(0));
  }

  @Test
  public void commandLineArgs() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("run", ":bin", "HELLO WORLD").assertSuccess();
    assertThat(
        result.getStdout(),
        containsString("HELLO WORLD"));
  }

  @Test
  public void nativeLibraries() throws IOException {
    assumeThat(packageStyle, equalTo(PythonBuckConfig.PackageStyle.INPLACE));
    assumeThat(
        "TODO(8667197): Native libs currently don't work on El Capitan",
        Platform.detect(),
        not(equalTo(Platform.MACOS)));
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("run", ":bin-with-native-libs").assertSuccess();
    assertThat(
        result.getStdout(),
        containsString("HELLO WORLD"));
  }

  @Test
  public void runFromGenrule() throws IOException {
    workspace.runBuckBuild(":gen").assertSuccess();
  }

  @Test
  public void arg0IsPreserved() throws IOException {
    workspace.writeContentsToPath("import sys; print(sys.argv[0])", "main.py");
    String arg0 = workspace.runBuckCommand("run", ":bin").assertSuccess().getStdout().trim();
    String output = workspace.runBuckCommand("targets", "--show-output", "//:bin")
        .assertSuccess()
        .getStdout()
        .trim();
    assertThat(
        arg0,
        endsWith(Splitter.on(" ").splitToList(output).get(1)));
  }

  @Test
  public void nativeLibsEnvVarIsPreserved() throws IOException {
    assumeThat(
        "TODO(8667197): Native libs currently don't work on El Capitan",
        Platform.detect(),
        not(equalTo(Platform.MACOS)));

    String nativeLibsEnvVarName =
        DefaultCxxPlatforms
            .build(new CxxBuckConfig(FakeBuckConfig.builder().build()))
            .getLd()
            .searchPathEnvVar();
    String originalNativeLibsEnvVar = "something";
    workspace.writeContentsToPath(
        String.format("import os; print(os.environ.get('%s'))", nativeLibsEnvVarName),
        "main_with_native_libs.py");
    Map<String, String> env = Maps.newHashMap(System.getenv());
    env.remove(nativeLibsEnvVarName);

    // Pre-set library path.
    String nativeLibsEnvVar =
        workspace.runBuckCommandWithEnvironmentAndContext(
            workspace.getPath(""),
            Optional.<NGContext>absent(),
            Optional.<BuckEventListener>absent(),
            Optional.of(
                ImmutableMap.<String, String>builder()
                    .putAll(env)
                    .put(nativeLibsEnvVarName, originalNativeLibsEnvVar)
                    .build()),
            "run",
            ":bin-with-native-libs")
            .assertSuccess()
            .getStdout()
            .trim();
    assertThat(
        nativeLibsEnvVar,
        equalTo(originalNativeLibsEnvVar));

    // Empty library path.
    nativeLibsEnvVar =
        workspace.runBuckCommandWithEnvironmentAndContext(
            workspace.getPath(""),
            Optional.<NGContext>absent(),
            Optional.<BuckEventListener>absent(),
            Optional.of(ImmutableMap.copyOf(env)),
            "run",
            ":bin-with-native-libs")
            .assertSuccess()
            .getStdout()
            .trim();
    assertThat(
        nativeLibsEnvVar,
        equalTo("None"));
  }

  @Test
  public void sysPathDoesNotIncludeWorkingDir() throws IOException {
    workspace.writeContentsToPath("import sys; print(sys.path[0])", "main.py");
    String sysPath0 = workspace.runBuckCommand("run", ":bin").assertSuccess().getStdout().trim();
    assertThat(
        sysPath0,
        not(equalTo("")));
  }

  @Test
  public void binaryIsCachedProperly() throws IOException {
    // Verify that the flow of build, upload to cache, clean, then re-build (and potentially
    // fetching from cache) results in a usable binary.
    workspace.writeContentsToPath("print('hello world')", "main.py");
    workspace.enableDirCache();
    workspace.runBuckBuild(":bin").assertSuccess();
    workspace.runBuckCommand("clean").assertSuccess();
    String stdout = workspace.runBuckCommand("run", ":bin").assertSuccess().getStdout().trim();
    assertThat(stdout, equalTo("hello world"));
  }

  @Test
  public void externalPexToolAffectsRuleKey() throws IOException {
    assumeThat(packageStyle, equalTo(PythonBuckConfig.PackageStyle.STANDALONE));

    ProjectWorkspace.ProcessResult firstResult =
        workspace.runBuckCommand(
            "targets",
            "-c",
            "python.path_to_pex=//:pex_tool",
            "--show-rulekey",
            "//:bin");
    String firstRuleKey = firstResult.assertSuccess().getStdout().trim();

    workspace.writeContentsToPath("changes", "pex_tool.sh");

    ProjectWorkspace.ProcessResult secondResult =
        workspace.runBuckCommand(
            "targets",
            "-c",
            "python.path_to_pex=//:pex_tool",
            "--show-rulekey",
            "//:bin");
    String secondRuleKey = secondResult.assertSuccess().getStdout().trim();

    assertThat(secondRuleKey, not(equalTo(firstRuleKey)));
  }

  private PythonBuckConfig getPythonBuckConfig() throws IOException {
    Config rawConfig =
        Config.createDefaultConfig(
            tmp.getRootPath(),
            ImmutableMap.<String, ImmutableMap<String, String>>of());
    BuckConfig buckConfig =
        new BuckConfig(
            rawConfig,
            new ProjectFilesystem(tmp.getRootPath()),
            Architecture.detect(),
            Platform.detect(),
            ImmutableMap.copyOf(System.getenv()));
    return new PythonBuckConfig(
        buckConfig,
        new ExecutableFinder());
  }

}
