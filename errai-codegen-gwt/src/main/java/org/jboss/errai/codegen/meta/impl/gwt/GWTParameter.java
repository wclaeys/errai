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

import com.google.gwt.core.ext.typeinfo.JParameter;
import com.google.gwt.core.ext.typeinfo.TypeOracle;
import org.jboss.errai.codegen.meta.MetaClass;
import org.jboss.errai.codegen.meta.MetaClassMember;
import org.jboss.errai.codegen.meta.MetaConstructor;
import org.jboss.errai.codegen.meta.MetaMethod;
import org.jboss.errai.codegen.meta.MetaParameter;

import java.lang.annotation.Annotation;

/**
 * @author Mike Brock <cbrock@redhat.com>
 */
public class GWTParameter extends MetaParameter {
  private final JParameter parameter;
  private final Annotation[] annotations;
  private final MetaClassMember declaredBy;
  private final TypeOracle oracle;

  GWTParameter(final TypeOracle oracle, final JParameter parameter, final MetaMethod declaredBy) {
    this.parameter = parameter;
    this.declaredBy = declaredBy;
    annotations = parameter.getAnnotations();
    this.oracle = oracle;
  }

  GWTParameter(final TypeOracle oracle, final JParameter parameter, final MetaConstructor declaredBy) {
    this.parameter = parameter;
    this.declaredBy = declaredBy;
    this.oracle = oracle;
    this.annotations = parameter.getAnnotations();
  }

  @Override
  public String getName() {
    return parameter.getName();
  }

  @Override
  public MetaClass getType() {
    return GWTUtil.eraseOrReturn(oracle, parameter.getType());
  }

  @Override
  public Annotation[] getAnnotations() {
    return annotations == null ? new Annotation[0] : annotations;
  }

  @SuppressWarnings("unchecked")
  @Override
  public final <A extends Annotation> A getAnnotation(final Class<A> annotation) {
    for (final Annotation a : getAnnotations()) {
      if (a.annotationType().equals(annotation)) return (A) a;
    }
    return null;
  }

  @Override
  public final boolean isAnnotationPresent(final Class<? extends Annotation> annotation) {
    return getAnnotation(annotation) != null;
  }

  @Override
  public MetaClassMember getDeclaringMember() {
    return declaredBy;
  }

  @Override
  public String toString() {
    return getType().getFullyQualifiedName();
  }
}
