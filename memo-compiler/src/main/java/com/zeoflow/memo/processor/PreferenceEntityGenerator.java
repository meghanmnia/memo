/*
 * Copyright (C) 2017 zeoflow
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zeoflow.memo.processor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.zeoflow.jx.file.ClassName;
import com.zeoflow.jx.file.FieldSpec;
import com.zeoflow.jx.file.MethodSpec;
import com.zeoflow.jx.file.ParameterSpec;
import com.zeoflow.jx.file.ParameterizedTypeName;
import com.zeoflow.jx.file.TypeName;
import com.zeoflow.jx.file.TypeSpec;
import com.zeoflow.memo.ConcealEncryption;
import com.zeoflow.memo.Memo;
import com.zeoflow.memo.NoEncryption;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.util.Elements;

import static com.zeoflow.memo.processor.PreferenceChangeListenerGenerator.CHANGED_LISTENER_PREFIX;
import static com.zeoflow.memo.processor.PreferenceChangeListenerGenerator.getChangeListenerFieldName;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

@SuppressWarnings({"WeakerAccess", "SpellCheckingInspection", "SimplifyStreamApiCallChains"})
public class PreferenceEntityGenerator
{

    private static final String CLAZZ_PREFIX = "_MemoEntity";
    private static final String FIELD_INSTANCE = "instance";
    private static final String FIELD_ENCRYPTION_KEY = "encryptionKey";
    private static final String CONSTRUCTOR_CONTEXT = "context";
    private static final String KEY_NAME_LIST = "keyNameList";
    private static final String PACKAGE_CONTEXT = "android.content.Context";
    private final PreferenceEntityAnnotatedClass annotatedClazz;
    private final Elements annotatedElementUtils;

    public PreferenceEntityGenerator(
            @NonNull PreferenceEntityAnnotatedClass annotatedClass, @NonNull Elements elementUtils)
    {
        this.annotatedClazz = annotatedClass;
        this.annotatedElementUtils = elementUtils;
    }

    public TypeSpec generate()
    {
        StringBuilder className = new StringBuilder(getClazzName());
        if(annotatedClazz.typeParameters.size() != 0)
        {
            className.append("<");
            for (String typeParameter : annotatedClazz.typeParameters)
            {
                className.append(typeParameter).append(",");
            }
            className.delete(className.length() - 1, className.length());
            className.append(">");
        }
        TypeSpec.Builder builder =
                TypeSpec.classBuilder(className.toString())
                        .addJavadoc("Preference class for $T\n", ClassName.get(annotatedClazz.annotatedElement))
                        .addJavadoc("Generated by Memo's Injector (https://github.com/zeoflow/memo).\n")
                        .addModifiers(PUBLIC)
                        .superclass(ClassName.get(annotatedClazz.annotatedElement))
                        .addFields(getFieldSpecs());

        if (annotatedClazz.isDefaultPreference)
        {
            builder.addMethods(addDefaultPreferenceConstructorsSpec());
        } else
        {
            builder.addMethods(addConstructorsSpec());
        }

        builder
                .addFields(getLiveDataFieldSpecs())
                .addMethods(addInstancesSpec())
                .addTypes(getOnChangedTypeSpecs())
                .addFields(getOnChangedFieldSpecs())
                .addMethods(getFieldMethodSpecs())
                .addMethods(getCompoundsGetter())
                .addMethod(getClearMethodSpec())
                .addMethod(getKeyNameListMethodSpec())
                .addMethod(getEntityNameMethodSpec())
                .addMethods(getAddOnChangedListenerSpecs())
                .addMethods(getRemoveOnChangedListenerSpecs())
                .addMethods(getClearOnChangedListenerSpecs());

        return builder.build();
    }

    private List<FieldSpec> getLiveDataFieldSpecs()
    {
        List<FieldSpec> fieldSpecs = new ArrayList<>();
        for (PreferenceKeyField keyField : this.annotatedClazz.keyFields)
        {
            if (!keyField.isObservable)
            {
                continue;
            }
            TypeName typeName = ParameterSpec.builder(keyField.typeName, keyField.keyName.toLowerCase()).build().type;
            switch (keyField.typeStringName)
            {
                case "Boolean":
                    typeName = TypeName.get(Boolean.class);
                    break;
                case "Int":
                    typeName = TypeName.get(Integer.class);
                    break;
                case "Float":
                    typeName = TypeName.get(Float.class);
                    break;
                case "Long":
                    typeName = TypeName.get(Long.class);
                    break;
            }
            fieldSpecs.add(FieldSpec.builder(
                    ParameterizedTypeName.get(
                            getMutableLiveDataClass(),
                            typeName
                    ),
                    keyField.keyName + "Observable",
                    Modifier.PRIVATE,
                    FINAL)
                    .initializer("new MutableLiveData<>()")
                    .build()
            );
        }
        return fieldSpecs;
    }
    private List<FieldSpec> getFieldSpecs()
    {
        List<FieldSpec> fieldSpecs = new ArrayList<>();
        fieldSpecs.add(FieldSpec.builder(getClassType(), FIELD_INSTANCE, PRIVATE, STATIC).build());
        FieldSpec.Builder encryptionField = FieldSpec.builder(
                String.class,
                FIELD_ENCRYPTION_KEY,
                PRIVATE,
                FINAL
        );
        encryptionField.initializer("$S", annotatedClazz.encryptionKey);
        fieldSpecs.add(encryptionField.build());
        return fieldSpecs;
    }
    private List<MethodSpec> addConstructorsSpec()
    {
        List<MethodSpec> methods = new ArrayList<>();
        MethodSpec autoConstructor = MethodSpec.constructorBuilder()
                .addModifiers(PRIVATE)
                .addJavadoc("AutoConstructor - the context is retrieved from StorageApplication")
                .addStatement("this(getContext())")
                .build();
        methods.add(autoConstructor);
        MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
                .addModifiers(PRIVATE)
                .addParameter(
                        ParameterSpec.builder(getContextPackageType(), CONSTRUCTOR_CONTEXT)
                                .addAnnotation(NonNull.class)
                                .build()
                );
        if (annotatedClazz.isEncryption)
        {
            constructor.addStatement(
                    "$T.init().setEncryption(new $T($N)).build()",
                    Memo.class,
                    ConcealEncryption.class,
                    FIELD_ENCRYPTION_KEY
            );
        } else
        {
            constructor.addStatement(
                    "$T.init().setEncryption(new $T()).build()",
                    Memo.class,
                    NoEncryption.class
            );
        }
        methods.add(constructor.build());
        return methods;
    }
    private List<MethodSpec> addDefaultPreferenceConstructorsSpec()
    {
        List<MethodSpec> methods = new ArrayList<>();
        MethodSpec autoConstructor = MethodSpec.constructorBuilder()
                .addModifiers(PRIVATE)
                .addJavadoc("AutoConstructor - the context is retrieved from StorageApplication")
                .addStatement("this(getContext())")
                .build();
        methods.add(autoConstructor);
        MethodSpec constructor = MethodSpec.constructorBuilder()
                .addModifiers(PRIVATE)
                .addParameter(
                        ParameterSpec.builder(getContextPackageType(), CONSTRUCTOR_CONTEXT)
                                .addAnnotation(NonNull.class)
                                .build())
                .build();
        methods.add(constructor);
        return methods;
    }
    private List<MethodSpec> addInstancesSpec()
    {
        List<MethodSpec> methods = new ArrayList<>();
        MethodSpec autoInstance = MethodSpec.methodBuilder("getInstance")
                .addModifiers(PUBLIC, STATIC)
                .addJavadoc("getContext() - the context is retrieved from StorageApplication")
                .addStatement("if ($N != null) return $N", FIELD_INSTANCE, FIELD_INSTANCE)
                .addStatement("$N = new $N()", FIELD_INSTANCE, getClazzName())
                .addStatement("return $N", FIELD_INSTANCE)
                .returns(getClassType())
                .build();
        methods.add(autoInstance);
        MethodSpec instance = MethodSpec.methodBuilder("getInstance")
                .addModifiers(PUBLIC, STATIC)
                .addParameter(
                        ParameterSpec.builder(getContextPackageType(), CONSTRUCTOR_CONTEXT)
                                .addAnnotation(NonNull.class)
                                .build())
                .addStatement("if ($N != null) return $N", FIELD_INSTANCE, FIELD_INSTANCE)
                .addStatement("$N = new $N($N)", FIELD_INSTANCE, getClazzName(), CONSTRUCTOR_CONTEXT)
                .addStatement("return $N", FIELD_INSTANCE)
                .returns(getClassType())
                .build();
        methods.add(instance);
        return methods;
    }

    private List<MethodSpec> getFieldMethodSpecs()
    {
        List<MethodSpec> methodSpecs = new ArrayList<>();
        this.annotatedClazz.keyFields.stream().forEach(
                annotatedFields ->
                {
                    PreferenceFieldMethodGenerator methodGenerator = new PreferenceFieldMethodGenerator(
                            annotatedFields,
                            annotatedClazz
                    );
                    methodSpecs.addAll(methodGenerator.getFieldMethods());
                });
        return methodSpecs;
    }

    private List<MethodSpec> getCompoundsGetter()
    {
        List<MethodSpec> methodSpecs = new ArrayList<>();
        for (Map.Entry<String[], ExecutableElement> entry : this.annotatedClazz.getterCompoundFunctionsList.entrySet())
        {
            methodSpecs.add(generateCompoundGetter(entry));
        }
        return methodSpecs;
    }
    private MethodSpec generateCompoundGetter(Map.Entry<String[], ExecutableElement> entry)
    {
        ExecutableElement element = entry.getValue();
        MethodSpec.Builder builder = MethodSpec.methodBuilder(
                element.getSimpleName().toString()
        ).addModifiers(PUBLIC);
        builder.returns(TypeName.get(element.getReturnType()));
        builder.addStatement("return " + getGetterStatement(element, entry.getKey()));
        return builder.build();
    }

    private String getGetterStatement(ExecutableElement element, String[] params)
    {
        StringBuilder content = new StringBuilder("super." + element.getSimpleName().toString() + "(");
        for(String param: params)
        {
            PreferenceKeyField preferenceParam = null;
            for (PreferenceKeyField preferenceKeyField: this.annotatedClazz.keyFields)
            {
                if (preferenceKeyField.keyName.equals(param))
                {
                    preferenceParam = preferenceKeyField;
                    break;
                }
            }
            if(preferenceParam != null)
            {
                if (preferenceParam.value instanceof String)
                {
                    content.append("Memo.get(\"")
                            .append(preferenceParam.keyName)
                            .append("\", \"")
                            .append(preferenceParam.value)
                            .append("\")");
                } else if (preferenceParam.value instanceof Float)
                {
                    content.append("Memo.get(\"")
                            .append(preferenceParam.keyName)
                            .append("\", ")
                            .append(preferenceParam.value)
                            .append("f)");
                } else
                {
                    content.append("Memo.get(\"")
                            .append(preferenceParam.keyName)
                            .append("\", ")
                            .append(preferenceParam.value)
                            .append(")");
                }
                content.append(", ");
            } else {
                // @todo-zeobot catch exception and alert the user that the field is invalid
            }
        }
        content.delete(content.length() - 2, content.length());
        return content + ")";
    }

    private MethodSpec getClearMethodSpec()
    {
        return MethodSpec.methodBuilder("clear")
                .addModifiers(PUBLIC)
                .addStatement("$T.deleteAll()", Memo.class)
                .build();
    }

    private MethodSpec getKeyNameListMethodSpec()
    {
        MethodSpec.Builder builder =
                MethodSpec.methodBuilder("get" + KEY_NAME_LIST)
                        .addModifiers(PUBLIC)
                        .returns(List.class)
                        .addStatement("List<String> $N = new $T<>()", KEY_NAME_LIST, ArrayList.class);

        this.annotatedClazz.keyNameFields.stream().forEach(
                keyName -> builder.addStatement("$N.add($S)", KEY_NAME_LIST, keyName));

        builder.addStatement("return $N", KEY_NAME_LIST);
        return builder.build();
    }

    private MethodSpec getEntityNameMethodSpec()
    {
        return MethodSpec.methodBuilder("getEntityName")
                .addModifiers(PUBLIC)
                .returns(String.class)
                .addStatement("return $S", annotatedClazz.entityName)
                .build();
    }

    private List<TypeSpec> getOnChangedTypeSpecs()
    {
        List<TypeSpec> typeSpecs = new ArrayList<>();
        for (PreferenceKeyField keyField : this.annotatedClazz.keyFields)
        {
            if (!keyField.isListener)
            {
                continue;
            }
            PreferenceChangeListenerGenerator changeListenerGenerator =
                    new PreferenceChangeListenerGenerator(keyField);
            typeSpecs.add(changeListenerGenerator.generateInterface());
        }
        return typeSpecs;
    }

    private List<FieldSpec> getOnChangedFieldSpecs()
    {
        List<FieldSpec> fieldSpecs = new ArrayList<>();
        for (PreferenceKeyField keyField : this.annotatedClazz.keyFields)
        {
            if (!keyField.isListener)
            {
                continue;
            }
            PreferenceChangeListenerGenerator changeListenerGenerator =
                    new PreferenceChangeListenerGenerator(keyField);
            fieldSpecs.add(changeListenerGenerator.generateField(getClazzName()));
        }
        return fieldSpecs;
    }

    private List<MethodSpec> getAddOnChangedListenerSpecs()
    {
        List<MethodSpec> methodSpecs = new ArrayList<>();
        for (PreferenceKeyField keyField : this.annotatedClazz.keyFields)
        {
            if (!keyField.isListener)
            {
                continue;
            }
            String onChangeListener =
                    keyField.keyName + CHANGED_LISTENER_PREFIX;
            PreferenceChangeListenerGenerator changeListenerGenerator =
                    new PreferenceChangeListenerGenerator(keyField);
            MethodSpec.Builder builder =
                    MethodSpec.methodBuilder("add" + StringUtils.toUpperCamel(onChangeListener))
                            .addModifiers(PUBLIC)
                            .addParameter(
                                    ParameterSpec.builder(
                                            changeListenerGenerator.getInterfaceType(getClazzName()), "listener")
                                            .build())
                            .addStatement(getChangeListenerFieldName(keyField.keyName) + ".add(listener)")
                            .returns(void.class);
            methodSpecs.add(builder.build());
        }
        return methodSpecs;
    }

    private List<MethodSpec> getRemoveOnChangedListenerSpecs()
    {
        List<MethodSpec> methodSpecs = new ArrayList<>();
        for (PreferenceKeyField keyField : this.annotatedClazz.keyFields)
        {
            if (!keyField.isListener)
            {
                continue;
            }
            String onChangeListener =
                    keyField.keyName + CHANGED_LISTENER_PREFIX;
            PreferenceChangeListenerGenerator changeListenerGenerator =
                    new PreferenceChangeListenerGenerator(keyField);
            MethodSpec.Builder builder =
                    MethodSpec.methodBuilder("remove" + StringUtils.toUpperCamel(onChangeListener))
                            .addModifiers(PUBLIC)
                            .addParameter(
                                    ParameterSpec.builder(
                                            changeListenerGenerator.getInterfaceType(getClazzName()), "listener")
                                            .build())
                            .addStatement(getChangeListenerFieldName(keyField.keyName) + ".remove(listener)")
                            .returns(void.class);
            methodSpecs.add(builder.build());
        }
        return methodSpecs;
    }

    private List<MethodSpec> getClearOnChangedListenerSpecs()
    {
        List<MethodSpec> methodSpecs = new ArrayList<>();
        for (PreferenceKeyField keyField : this.annotatedClazz.keyFields)
        {
            if (!keyField.isListener)
            {
                continue;
            }
            String onChangeListener =
                    keyField.keyName + CHANGED_LISTENER_PREFIX;
            MethodSpec.Builder builder =
                    MethodSpec.methodBuilder("clear" + StringUtils.toUpperCamel(onChangeListener) + "s")
                            .addModifiers(PUBLIC)
                            .addStatement(getChangeListenerFieldName(keyField.keyName) + ".clear()")
                            .returns(void.class);
            methodSpecs.add(builder.build());
        }
        return methodSpecs;
    }

    private ClassName getClassType()
    {
        return ClassName.get(annotatedClazz.packageName, getClazzName());
    }

    private String getClazzName()
    {
        return annotatedClazz.entityName + CLAZZ_PREFIX;
    }

    private TypeName getContextPackageType()
    {
        return TypeName.get(annotatedElementUtils.getTypeElement(PACKAGE_CONTEXT).asType());
    }

    private ClassName getMutableLiveDataClass()
    {
        return ClassName.get("androidx.lifecycle", "MutableLiveData");
    }

}
