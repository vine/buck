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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.step.TestExecutionContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PexStepTest {

  @Test
  public void testCommandLine() {
    PexStep step = new PexStep(
        Paths.get("pex.py"),
        Paths.get("/usr/local/bin/python"),
        Paths.get("/tmp"),
        Paths.get("/dest"),
        "entry_point.main",
        /* modules */ ImmutableMap.<Path, Path>of(),
        /* resources */ ImmutableMap.<Path, Path>of(),
        /* nativeLibraries */ ImmutableMap.<Path, Path>of(),
        /* packages */ ImmutableList.<Path>of(),
        /* zipSafe */ true);
    assertEquals(
        ImmutableList.of("/usr/local/bin/python", "pex.py",
                         "--python", "/usr/local/bin/python",
                         "--entry-point", "entry_point.main",
                         "/dest"),
        step.getShellCommandInternal(TestExecutionContext.newInstance()));
  }

  @Test
  public void testCommandLineNoZipSafe() {
    PexStep step = new PexStep(
        Paths.get("pex.py"),
        Paths.get("/usr/local/bin/python"),
        Paths.get("/tmp"),
        Paths.get("/dest"),
        "entry_point.main",
        /* modules */ ImmutableMap.<Path, Path>of(),
        /* resources */ ImmutableMap.<Path, Path>of(),
        /* nativeLibraries */ ImmutableMap.<Path, Path>of(),
        /* packages */ ImmutableList.<Path>of(),
        /* zipSafe */ false);
    assertEquals(
        ImmutableList.of("/usr/local/bin/python", "pex.py",
                         "--python", "/usr/local/bin/python",
                         "--entry-point", "entry_point.main",
                         "--no-zip-safe", "/dest"),
        step.getShellCommandInternal(TestExecutionContext.newInstance()));
  }

}
