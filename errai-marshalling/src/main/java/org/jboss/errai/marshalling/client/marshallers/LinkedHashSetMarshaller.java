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

package org.jboss.errai.marshalling.client.marshallers;

import org.jboss.errai.marshalling.client.api.MarshallingSession;
import org.jboss.errai.marshalling.client.api.annotations.ClientMarshaller;
import org.jboss.errai.marshalling.client.api.annotations.ImplementationAliases;
import org.jboss.errai.marshalling.client.api.annotations.ServerMarshaller;
import org.jboss.errai.marshalling.client.api.json.EJArray;

import java.util.AbstractSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * @author Mike Brock <cbrock@redhat.com>
 */
@ClientMarshaller @ServerMarshaller
public class LinkedHashSetMarshaller extends AbstractCollectionMarshaller<LinkedHashSet> {

  @Override
  public Class<LinkedHashSet> getTypeHandled() {
    return LinkedHashSet.class;
  }

  @Override
  public LinkedHashSet[] getEmptyArray() {
    return new LinkedHashSet[0];
  }
  
  @Override
  public LinkedHashSet doDemarshall(final EJArray o, final MarshallingSession ctx) {
    return marshallToCollection(new LinkedHashSet<Object>(o.size()), o, ctx);
  }
}
