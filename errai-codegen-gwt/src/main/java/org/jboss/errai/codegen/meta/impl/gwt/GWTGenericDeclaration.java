/*
 * Copyright 2011 JBoss, by Red Hat, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.errai.codegen.meta.impl.gwt;

import java.util.ArrayList;
import java.util.List;

import org.jboss.errai.codegen.meta.MetaGenericDeclaration;
import org.jboss.errai.codegen.meta.MetaTypeVariable;

import com.google.gwt.core.ext.typeinfo.JGenericType;
import com.google.gwt.core.ext.typeinfo.JTypeParameter;
import com.google.gwt.core.ext.typeinfo.TypeOracle;

/**
 * @author Mike Brock <cbrock@redhat.com>
 */
public class GWTGenericDeclaration implements MetaGenericDeclaration {
  private final JGenericType genericType;
  private final TypeOracle oracle;

  public GWTGenericDeclaration(final TypeOracle oracle, final JGenericType genericType) {
    this.oracle = oracle;
    this.genericType = genericType;
  }

  @Override
  public MetaTypeVariable[] getTypeParameters() {
    final List<MetaTypeVariable> typeVariables = new ArrayList<MetaTypeVariable>();

    for (final JTypeParameter typeParameter : genericType.getTypeParameters()) {
      typeVariables.add(new GWTTypeVariable(oracle, typeParameter));
    }

    return typeVariables.toArray(new MetaTypeVariable[typeVariables.size()]);
  }

  @Override
  public String getName() {
    return genericType.getParameterizedQualifiedSourceName();
  }
}
