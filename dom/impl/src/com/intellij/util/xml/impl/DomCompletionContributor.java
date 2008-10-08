/*
 * Copyright (c) 2000-2005 by JetBrains s.r.o. All Rights Reserved.
 * Use is subject to license terms.
 */
package com.intellij.util.xml.impl;

import com.intellij.codeInsight.completion.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ProcessingContext;
import com.intellij.util.containers.HashSet;
import com.intellij.xml.util.XmlUtil;
import com.intellij.xml.XmlAttributeDescriptor;

/**
 * @author peter
 */
public class DomCompletionContributor extends CompletionContributor{
  private final GenericValueReferenceProvider myProvider = new GenericValueReferenceProvider();

  @Override
  public boolean fillCompletionVariants(final CompletionParameters parameters, final CompletionResultSet result) {
    if (parameters.getCompletionType() != CompletionType.BASIC) return true;

    final PsiElement element = PsiTreeUtil.getParentOfType(parameters.getPosition(), XmlTag.class, XmlAttributeValue.class);
    if (element == null) return true;

    if (ApplicationManager.getApplication().runReadAction(new Computable<Boolean>() {
      public Boolean compute() {
        return isSchemaEnumerated(element);
      }
    }).booleanValue()) return true;

    final PsiReference[] references = ApplicationManager.getApplication().runReadAction(new Computable<PsiReference[]>() {
      public PsiReference[] compute() {
        return myProvider.getReferencesByElement(element, new ProcessingContext());
      }
    });
    if (references.length > 0) {
      LegacyCompletionContributor.completeReference(parameters, result, new XmlCompletionData());
      return false;
    }

    return true;
  }

  public static boolean isSchemaEnumerated(final PsiElement element) {
    if (element instanceof XmlTag) {
      final XmlTag simpleContent = XmlUtil.getSchemaSimpleContent((XmlTag)element);
      if (simpleContent != null && XmlUtil.collectEnumerationValues(simpleContent, new HashSet<String>())) {
        return true;
      }
    }
    if (element instanceof XmlAttributeValue) {
      final PsiElement parent = element.getParent();
      if (parent instanceof XmlAttribute) {
        final XmlAttributeDescriptor descriptor = ((XmlAttribute)parent).getDescriptor();
        if (descriptor != null && descriptor.isEnumerated()) return true;
      }
    }
    return false;
  }
}
