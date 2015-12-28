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

import com.facebook.buck.util.immutables.BuckStyleImmutable;

import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractPythonVersion {

  @Value.Parameter
  public abstract String getInterpreterName();

  @Value.Parameter
  public abstract String getVersionString();  // X.Y.Z

  public String getPexCompatibilityVersion() {
    StringBuilder sb = new StringBuilder(20);
    sb.append(getInterpreterName());
    sb.append(" ");
    sb.append(getVersionString().replace('.', ' '));
    return sb.toString();
  }

  @Override
  public String toString() {
    return getInterpreterName() + " " + getVersionString();
  }

}
