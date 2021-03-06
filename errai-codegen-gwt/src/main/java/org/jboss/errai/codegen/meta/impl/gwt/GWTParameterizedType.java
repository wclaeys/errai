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

import org.jboss.errai.codegen.meta.MetaType;
import org.jboss.errai.codegen.meta.impl.AbstractMetaParameterizedType;

import com.google.gwt.core.ext.typeinfo.JClassType;
import com.google.gwt.core.ext.typeinfo.JParameterizedType;
import com.google.gwt.core.ext.typeinfo.TypeOracle;

/**
 * @author Mike Brock <cbrock@redhat.com>
 */
public class GWTParameterizedType extends AbstractMetaParameterizedType {
  private final JParameterizedType parameterizedType;
  private final TypeOracle oracle;

  public GWTParameterizedType(final TypeOracle oracle, final JParameterizedType parameterizedType) {
    this.parameterizedType = parameterizedType;
    this.oracle = oracle;
  }

  @Override
  public MetaType[] getTypeParameters() {
    final List<MetaType> types = new ArrayList<MetaType>();
    for (final JClassType parm : parameterizedType.getTypeArgs()) {
      types.add(GWTUtil.eraseOrReturn(oracle, parm));
    }
    return types.toArray(new MetaType[types.size()]);
  }

  @Override
  public MetaType getOwnerType() {
    return GWTClass.newInstance(oracle, parameterizedType.getEnclosingType());
  }

  @Override
  public MetaType getRawType() {
    return GWTClass.newInstance(oracle, parameterizedType.getRawType());
  }

  @Override
  public String toString() {
    return getName();
  }

  @Override
  public String getName() {
    return parameterizedType.getParameterizedQualifiedSourceName();
  }
}
