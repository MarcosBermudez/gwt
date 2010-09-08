/*
 * Copyright 2010 Google Inc.
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
package com.google.gwt.editor.client.impl;

import com.google.gwt.core.client.GWT;
import com.google.gwt.editor.client.Editor;
import com.google.gwt.event.shared.EventBus;
import com.google.gwt.event.shared.HandlerRegistration;

import java.util.Collections;
import java.util.Set;

import javax.validation.ConstraintViolation;

/**
 * A limited EditorDelegate for editing standard bean-like objects.
 * 
 * @param <T> the type being editor
 * @param <E> the type of editor
 */
public abstract class SimpleBeanEditorDelegate<T, E extends Editor<T>> extends
    AbstractEditorDelegate<T, E> {

  private static final HandlerRegistration FAKE_REGISTRATION = new HandlerRegistration() {
    public void removeHandler() {
    }
  };

  @Override
  public T ensureMutable(T object) {
    return object;
  }

  @Override
  public void initialize(EventBus eventBus, String pathSoFar, T object, E editor) {
    super.initialize(eventBus, pathSoFar, object, editor);
  }

  @Override
  public HandlerRegistration subscribe() {
    return FAKE_REGISTRATION;
  }

  @Override
  public Set<ConstraintViolation<T>> validate(T object) {
    GWT.log("SimpleBeanEditorDelegate.validate() not yet implemented");
    return Collections.emptySet();
  }
}