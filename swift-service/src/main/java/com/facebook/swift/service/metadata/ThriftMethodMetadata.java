/*
 * Copyright (C) 2012 Facebook, Inc.
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
package com.facebook.swift.service.metadata;

import com.facebook.swift.codec.ThriftField;
import com.facebook.swift.codec.ThriftStruct;
import com.facebook.swift.codec.metadata.ThriftCatalog;
import com.facebook.swift.codec.metadata.ThriftConstructorInjection;
import com.facebook.swift.codec.metadata.ThriftExtraction;
import com.facebook.swift.codec.metadata.ThriftFieldMetadata;
import com.facebook.swift.codec.metadata.ThriftInjection;
import com.facebook.swift.codec.metadata.ThriftMethodInjection;
import com.facebook.swift.codec.metadata.ThriftParameterInjection;
import com.facebook.swift.codec.metadata.ThriftType;
import com.facebook.swift.codec.metadata.TypeCoercion;
import com.facebook.swift.service.ThriftException;
import com.facebook.swift.service.ThriftMethod;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.ListenableFuture;

import org.apache.thrift.TException;

import javax.annotation.concurrent.Immutable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.facebook.swift.codec.ThriftField.Requiredness;
import static com.facebook.swift.codec.metadata.FieldKind.THRIFT_FIELD;
import static com.facebook.swift.codec.metadata.ReflectionHelper.extractParameterNames;
import static com.google.common.base.Preconditions.checkState;

@Immutable
public class ThriftMethodMetadata
{
    private final String name;
    private final String qualifiedName;
    private final ThriftType returnType;
    private final List<ThriftFieldMetadata> parameters;
    private final Method method;
    private final ImmutableMap<Short, ThriftType> exceptions;
    private final ImmutableList<String> documentation;
    private final boolean oneway;

    public ThriftMethodMetadata(String name, String qualifiedName, ThriftType returnType, List<ThriftFieldMetadata> parameters, Method method, ImmutableMap<Short, ThriftType> exceptions,
            ImmutableList<String> documentation, boolean oneway) {
        super();
        this.name = name;
        this.qualifiedName = qualifiedName;
        this.returnType = returnType;
        this.parameters = parameters;
        this.method = method;
        this.exceptions = exceptions;
        this.documentation = documentation;
        this.oneway = oneway;
    }

    /**
     * Extract a ThriftMethodMetadata object from a method. The method must have the @ThriftMethod annotation.
     * 
     * @param serviceName
     * @param method
     * @param catalog
     */
    public ThriftMethodMetadata(String serviceName, Method method, ThriftCatalog catalog )
    {	
    	this( serviceName, method, catalog, false );
    }

    /**
     * Extract a ThriftMethodMetadata object from a method. The method should
     * have the @ThriftMethod annotation.
     * 
     * @param serviceName
     * @param method
     * @param catalog
     * @param allowUnannotated
     *            If true, the @ThriftMethod may be omitted and an attempt is
     *            made to automatically extract the data that would have been
     *            held in the annotation.
     */
    public ThriftMethodMetadata(String serviceName, Method method, ThriftCatalog catalog, boolean allowUnannotated)
    {
        Preconditions.checkNotNull(method, "method is null");
        Preconditions.checkNotNull(catalog, "catalog is null");
        Preconditions.checkArgument(!Modifier.isStatic(method.getModifiers()), "Method %s is static", method.toGenericString());

        this.method = method;
        
        ThriftMethod thriftMethod = method.getAnnotation(ThriftMethod.class);
        if (thriftMethod!=null)
        {
            Preconditions.checkArgument(thriftMethod != null, "Method is not annotated with @ThriftMethod: " + serviceName + "." + method.getName());
			if (thriftMethod.value().length() == 0) {
				name = method.getName();
			}
			else {
				name = thriftMethod.value();
			}
	        this.oneway = thriftMethod.oneway();
	        exceptions = buildExceptionMap(catalog, thriftMethod, method);
	        documentation = ThriftCatalog.getThriftDocumentation(method);
        }
    	else{
            Preconditions.checkArgument(allowUnannotated, "Method is not annotated with @ThriftMethod: " + serviceName + "." + method.getName());
    		
    		// Auto
    		name = method.getName();
    		// TODO. Provide override as parameter?
            this.oneway = false;
            ImmutableMap.Builder<Short, ThriftType> exceptionsBuilder = ImmutableMap.builder();
            
            // TODO. Auto extract methods
            exceptions = exceptionsBuilder.build();
            
            // TODO. Auto extract documentation
            documentation =  ImmutableList.<String>of();
    	}
    	
        this.qualifiedName = serviceName + "." + name;

        returnType = catalog.getThriftType(method.getGenericReturnType());

        parameters = toMethodParameters( catalog, method, method.getGenericParameterTypes() );
    }
    
    /**
     * Method parameters are supplied separately -- allows scala and other language to access no-erased types.
     */
    public static List<ThriftFieldMetadata> toMethodParameters(ThriftCatalog catalog, Method method, Type[] parameterTypes) {
        ImmutableList.Builder<ThriftFieldMetadata> builder = ImmutableList.builder();
        String[] parameterNames = extractParameterNames(method);
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        for (int index = 0; index < parameterTypes.length; index++) {
            ThriftField thriftField = null;
            for (Annotation annotation : parameterAnnotations[index]) {
                if (annotation instanceof ThriftField) {
                    thriftField = (ThriftField) annotation;
                    break;
                }
            }

            short parameterId = Short.MIN_VALUE;
            String parameterName = null;
            Requiredness parameterRequiredness = Requiredness.UNSPECIFIED;
            if (thriftField != null) {
                parameterId = thriftField.value();
                parameterRequiredness = thriftField.requiredness();
                if (!thriftField.name().isEmpty()) {
                    parameterName = thriftField.name();
                }
            }
            if (parameterId == Short.MIN_VALUE) {
                parameterId = (short) (index + 1);
            }
            if (parameterName == null) {
                parameterName = parameterNames[index];
            }

            Type parameterType = parameterTypes[index];

            ThriftType thriftType = catalog.getThriftType(parameterType);

            ThriftInjection parameterInjection = new ThriftParameterInjection(parameterId, parameterName, index, parameterType);

            if (parameterRequiredness == Requiredness.UNSPECIFIED) {
                // There is only one field injection used to build metadata for method parameters, and if a
                // single injection point has UNSPECIFIED requiredness, that resolves to NONE.
                parameterRequiredness = Requiredness.NONE;
            }

            ThriftFieldMetadata fieldMetadata = new ThriftFieldMetadata(
                    parameterId,
                    parameterRequiredness,
                    thriftType,
                    parameterName,
                    THRIFT_FIELD,
                    ImmutableList.of(parameterInjection),
                    Optional.<ThriftConstructorInjection>absent(),
                    Optional.<ThriftMethodInjection>absent(),
                    Optional.<ThriftExtraction>absent(),
                    Optional.<TypeCoercion>absent()
            );
            builder.add(fieldMetadata);
        }
        return builder.build();
    }

    public String getName()
    {
        return name;
    }

    public ThriftType getReturnType()
    {
        return returnType;
    }

    public List<ThriftFieldMetadata> getParameters()
    {
        return parameters;
    }

    public Map<Short, ThriftType> getExceptions()
    {
        return exceptions;
    }

    public ThriftType getException(short id)
    {
        return exceptions.get(id);
    }

    public ImmutableList<String> getDocumentation()
    {
        return documentation;
    }

    public Method getMethod()
    {
        return method;
    }

    public boolean getOneway() {
        return oneway;
    }

    static public ImmutableMap<Short, ThriftType> buildExceptionMap(
            ThriftCatalog catalog,
            ThriftMethod thriftMethod, 
            Method method ) {
        ImmutableMap.Builder<Short, ThriftType> exceptions = ImmutableMap.builder();
        Set<Type> exceptionTypes = new HashSet<>();
        int customExceptionCount = 0;

        if (thriftMethod.exception().length > 0) {
            for (ThriftException thriftException : thriftMethod.exception()) {
                exceptions.put(thriftException.id(), catalog.getThriftType(thriftException.type()));
                checkState(exceptionTypes.add(thriftException.type()), "ThriftMethod.exception contains more than one value for %s", thriftException.type());
            }
        }

        for (Class<?> exceptionClass : method.getExceptionTypes()) {
            if (exceptionClass.isAssignableFrom(TException.class)) {
                // the built-in exception types don't need special treatment
                continue;
            }

            if (exceptionClass.isAnnotationPresent(ThriftStruct.class)) {
                ++customExceptionCount;

                if (!exceptionTypes.contains(exceptionClass)) {
                    // there is no rhyme or reason to the order exception types are given to us,
                    // so we can only infer the id once
                    checkState(customExceptionCount <= 1, "ThriftMethod.exception annotation value must be specified when more than one custom exception is thrown.");
                    exceptions.put((short) 1, catalog.getThriftType(exceptionClass));
                }
            }
        }

        return exceptions.build();
    }

    public boolean isAsync()
    {
        Type returnType = method.getGenericReturnType();
        Class<?> rawType = TypeToken.of(returnType).getRawType();
        return ListenableFuture.class.isAssignableFrom(rawType);
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final ThriftMethodMetadata that = (ThriftMethodMetadata) o;

        return Objects.equals(name, that.name) && Objects.equals(returnType, that.returnType) &&
                Objects.equals(parameters, that.parameters) && Objects.equals(method, that.method) &&
                Objects.equals(exceptions, that.exceptions) && Objects.equals(oneway, that.oneway);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(name, returnType, parameters, method, exceptions, oneway);
    }

    public String getQualifiedName()
    {
        return qualifiedName;
    }
}
