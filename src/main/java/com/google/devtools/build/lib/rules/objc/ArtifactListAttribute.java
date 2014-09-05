// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.objc;

import static com.google.devtools.build.lib.packages.Type.LABEL_LIST;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.view.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.view.RuleContext;

import java.util.Locale;

/**
 * Attributes allowed on {@code objc_*} rules that are artifact lists.
 */
public enum ArtifactListAttribute {
  HDRS, SRCS, NON_ARC_SRCS, ARCHIVES;

  public String attrName() {
    return name().toLowerCase(Locale.US);
  }

  /**
   * The artifacts specified by this attribute on the given rule. Returns an empty sequence if the
   * attribute is omitted or not available on the rule type.
   */
  public Iterable<Artifact> get(RuleContext context) {
    if (context.attributes().getAttributeDefinition(attrName()) == null) {
      return ImmutableList.of();
    } else {
      return context.getPrerequisiteArtifacts(attrName(), Mode.TARGET);
    }
  }

  /**
   * The labels specified by this attribute on the given rule. Returns an empty sequence if the
   * attribute is omitted or not available on the rule type.
   */
  public Iterable<Label> get(AttributeMap map) {
    return (map.getAttributeDefinition(attrName()) == null)
        ? ImmutableList.<Label>of() : map.get(attrName(), LABEL_LIST);
  }
}
