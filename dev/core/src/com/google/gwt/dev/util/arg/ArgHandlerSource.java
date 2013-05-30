/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.dev.util.arg;

import com.google.gwt.thirdparty.guava.common.base.Joiner;
import com.google.gwt.thirdparty.guava.common.collect.ImmutableMap;
import com.google.gwt.util.tools.ArgHandlerString;

import java.util.Map;

/**
 * Set the Java source level compatibility.
 */
public class ArgHandlerSource extends ArgHandlerString {
  private static final Map<String, SourceLevel> sourceLevelsByString;

  static {
    ImmutableMap.Builder<String, SourceLevel> builder = ImmutableMap.<String, SourceLevel>builder();
    for (SourceLevel sourceLevel : SourceLevel.values()) {
      builder.put(sourceLevel.getStringValue(), sourceLevel);
      builder.put(sourceLevel.getAltStringValue(), sourceLevel);
    }
    sourceLevelsByString = builder.build();
  }

  private final OptionSource options;

  public ArgHandlerSource(OptionSource options) {
    this.options = options;
  }

  @Override
  public String[] getDefaultArgs() {
    return new String[]{getTag(), OptionSource.DEFAULT_SOURCE_LEVEL.getStringValue()};
  }

  @Override
  public String getPurpose() {
    return "Specifies Java source level (defaults to " + OptionSource.DEFAULT_SOURCE_LEVEL + ")";
  }

  @Override
  public String getTag() {
    return "-sourceLevel";
  }

  @Override
  public String[] getTagArgs() {
    return new String[]{"[" + Joiner.on(",").join(SourceLevel.values()) + "]"};
  }

  @Override
  public boolean setString(String value) {
    SourceLevel level = sourceLevelsByString.get(value);
    if (value == null) {
      System.err.println("Source level must be one of [" +
          Joiner.on(",").join(SourceLevel.values()) + "].");
      return false;
    }
    options.setSourceLevel(level);
    return true;
  }
}
