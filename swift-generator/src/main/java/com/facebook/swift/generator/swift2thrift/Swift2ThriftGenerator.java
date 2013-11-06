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
package com.facebook.swift.generator.swift2thrift;

import com.facebook.swift.codec.ThriftCodecManager;
import com.facebook.swift.codec.ThriftProtocolType;
import com.facebook.swift.codec.metadata.FieldType;
import com.facebook.swift.codec.metadata.ReflectionHelper;
import com.facebook.swift.codec.metadata.ThriftFieldMetadata;
import com.facebook.swift.codec.metadata.ThriftStructMetadata;
import com.facebook.swift.codec.metadata.ThriftType;
import com.facebook.swift.generator.swift2thrift.template.ThriftContext;
import com.facebook.swift.generator.swift2thrift.template.ThriftServiceMetadataRenderer;
import com.facebook.swift.generator.swift2thrift.template.ThriftTypeRenderer;
import com.facebook.swift.generator.util.TemplateLoader;
import com.facebook.swift.service.ThriftService;
import com.facebook.swift.service.metadata.ThriftMethodMetadata;
import com.facebook.swift.service.metadata.ThriftServiceMetadata;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stringtemplate.v4.AutoIndentWriter;
import org.stringtemplate.v4.ST;

import javax.annotation.Nullable;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Swift2ThriftGenerator {
    private static final Logger LOG = LoggerFactory.getLogger(Swift2ThriftGenerator.class);
    private final OutputStreamWriter outputStreamWriter;
    private final boolean verbose;
    private final ThriftCodecManager codecManager = new ThriftCodecManager();
    private final String defaultPackage;
    private final String allowMultiplePackages;     // null means don't allow
    private ThriftTypeRenderer thriftTypeRenderer;
    private List<ThriftType> thriftTypes = Lists.newArrayList();
    private List<ThriftServiceMetadata> thriftServices = Lists.newArrayList();
    private String packageName;
    // includeMap maps a ThriftType or a ThriftServiceMetadata to the include that defines it
    private Map<Object, String> includeMap = Maps.newHashMap();
    private Set<ThriftType> usedIncludedTypes = Sets.newHashSet();
    private Set<ThriftServiceMetadata> usedIncludedServices = Sets.newHashSet();
    private Set<ThriftType> knownTypes = Sets.newHashSet(ThriftType.BOOL, ThriftType.BYTE, ThriftType.I16, ThriftType.I32,
            ThriftType.I64, ThriftType.DOUBLE, ThriftType.STRING,
            new ThriftType(ThriftType.BOOL, Boolean.class), new ThriftType(ThriftType.BYTE, Byte.class),
            new ThriftType(ThriftType.I16, Short.class), new ThriftType(ThriftType.I32, Integer.class),
            new ThriftType(ThriftType.I64, Long.class), new ThriftType(ThriftType.DOUBLE, Double.class),
            new ThriftType(ThriftType.STRING, String.class), new ThriftType(ThriftType.STRING, byte[].class));
    private Set<ThriftServiceMetadata> knownServices = Sets.newHashSet();
    private Map<String, String> namespaceMap;

    Swift2ThriftGenerator(final Swift2ThriftGeneratorConfig config) throws FileNotFoundException
    {
        this.verbose = config.isVerbose();
        String defaultPackage = config.getDefaultPackage();
        
        if (defaultPackage.isEmpty()) {
            this.defaultPackage = "";
        } else {
            this.defaultPackage = defaultPackage + ".";
        }
        
        OutputStream os = config.getOutputFile() != null ? new FileOutputStream(config.getOutputFile()) : System.out;
        this.outputStreamWriter = new OutputStreamWriter(os, Charsets.UTF_8);
        Map<String, String> paramIncludeMap = config.getIncludeMap();
        // create a type renderer with an empty map until we build it
        this.thriftTypeRenderer = new ThriftTypeRenderer(ImmutableMap.<ThriftType,String>of());
        for (Map.Entry<String, String> entry: paramIncludeMap.entrySet()) {
            Class<?> cls = load(entry.getKey());
            if (cls == null) {
                continue;
            }
            
            Object result = convertToThrift(cls);
            if (result != null) {
                this.includeMap.put(result, entry.getValue());
            }
        }
        
        this.namespaceMap = config.getNamespaceMap();
        this.allowMultiplePackages = config.isAllowMultiplePackages();
    }

    public void parse(Iterable<String> inputs) throws IOException 
    {
        boolean loadErrors = false;
        
        if (allowMultiplePackages != null) {
            packageName = allowMultiplePackages;
        }
        
        for (String className: inputs) {
            Class<?> cls = load(className);
            if (cls == null) {
                loadErrors = true;
                continue;
            }
            
            if (packageName == null) {
                packageName = cls.getPackage().getName();
            } else if (!packageName.equals(cls.getPackage().getName())) {
                if (allowMultiplePackages == null) {
                    throw new IllegalStateException(
                        String.format("Class %s is in package %s, previous classes were in package %s",
                            cls.getName(), cls.getPackage().getName(), packageName));
                }
            }
            
            Object result = convertToThrift(cls);
            if (result instanceof ThriftType) {
                thriftTypes.add((ThriftType)result);
            } else if (result instanceof ThriftServiceMetadata) {
                thriftServices.add((ThriftServiceMetadata)result);
            }
            // if the class we just loaded was also in the include map, remove it from there
            includeMap.remove(result);
        }
        if (loadErrors) {
            LOG.error("Couldn't load some classes");
            return;
        }
        
        if (verify()) {
            gen();
        } else {
            LOG.error("Errors found during verification.");
        }
    }

    private String getFullClassName(String className)
    {
        if (className.indexOf('.') == -1) {
            return defaultPackage + className;
        } else {
            return className;
        }
    }

    private boolean verify()
    {
        // no short-circuit
        return verifyTypes() & verifyServices();
    }

    // verifies that all types are known (in thriftTypes or in include map)
    // and does a topological sort of thriftTypes in dependency order
    private boolean verifyTypes()
    {
        SuccessAndResult<ThriftType> output = topologicalSort(thriftTypes, new Predicate<ThriftType>()
        {
            @Override
            public boolean apply(@Nullable ThriftType t)
            {
                ThriftProtocolType proto = t.getProtocolType();
                if (proto == ThriftProtocolType.ENUM) {
                    knownTypes.add(t);
                    return true;
                } else if (proto == ThriftProtocolType.STRUCT) {
                    return verifyStruct(t, true);
                } else {
                    Preconditions.checkState(false, "Top-level non-enum and non-struct?");
                    return false;   // silence compiler
                }
            }
        });
        if (output.success) {
            thriftTypes = output.result;
            return true;
        } else {
            for (ThriftType t: output.result) {
                // we know it's gonna fail, we just want the precise error message
                verifyStruct(t, false);
            }
            return false;
        }
    }

    private boolean verifyServices()
    {
        SuccessAndResult<ThriftServiceMetadata> output = topologicalSort(thriftServices, new Predicate<ThriftServiceMetadata>()
        {
            @Override
            public boolean apply(@Nullable ThriftServiceMetadata thriftServiceMetadata)
            {
                return verifyService(thriftServiceMetadata, true);
            }
        });
        if (output.success) {
            thriftServices = output.result;
            return true;
        } else {
            for (ThriftServiceMetadata s: output.result) {
                // we know it's gonna fail, we just want the precise error message
                verifyService(s, false);
            }
            return false;
        }
    }

    private class SuccessAndResult<T>
    {
        public boolean success;
        public List<T> result;
        SuccessAndResult(boolean success, List<T> result)
        {
            this.success = success;
            this.result = result;
        }
    }
    
    private <T> SuccessAndResult<T> topologicalSort(List<T> list, Predicate<T> isKnown)
    {
        List<T> remaining = list;
        List<T> newList = Lists.newArrayList();
        int prevSize = 0;
        while (prevSize != remaining.size()) {
            prevSize = remaining.size();
            List<T> bad = Lists.newArrayList();
            for (T t: remaining) {
                if (isKnown.apply(t))
                    newList.add(t);
                else
                    bad.add(t);
            }
            remaining = bad;
        }
        if (prevSize == 0) {
            return new SuccessAndResult(true, newList);
        } else {
            return new SuccessAndResult(false, remaining);
        }
    }

    private boolean verifyService(ThriftServiceMetadata service, boolean quiet)
    {
        boolean ok = true;
        ThriftServiceMetadata parent = service.getParentService();
        if (parent != null && !knownServices.contains(parent)) {
            if (includeMap.containsKey(parent)) {
                usedIncludedServices.add(parent);
            } else {
                ok = false;
                if (!quiet) {
                    LOG.error("Unknown parent service {} in {}",
                            parent.getName(),
                            service.getName());
                }
            }
        }
        
        for (Map.Entry<String, ThriftMethodMetadata> method : service.getDeclaredMethods().entrySet()) {
            for (ThriftFieldMetadata f : method.getValue().getParameters()) {
                if (!verifyField(f.getThriftType())) {
                    ok = false;
                    if (!quiet) {
                        LOG.error("Unknown argument type {} in {}.{}",
                                thriftTypeRenderer.toString(f.getThriftType()),
                                service.getName(),
                                method.getKey());
                    }
                }
            }
            
            for (ThriftType ex : method.getValue().getExceptions().values()) {
                if (!verifyField(ex)) {
                    ok = false;
                    if (!quiet) {
                        LOG.error("Unknown exception type {} in {}.{}",
                                thriftTypeRenderer.toString(ex),
                                service.getName(),
                                method.getKey());
                    }
                }
            }
            
            if (!method.getValue().getReturnType().equals(ThriftType.VOID) &&
                    !verifyField(method.getValue().getReturnType())) {
                ok = false;
                if (!quiet) {
                    LOG.error("Unknown return type {} in {}.{}",
                            thriftTypeRenderer.toString(method.getValue().getReturnType()),
                            service.getName(),
                            method.getKey());
                }
            }
        }
        
        knownServices.add(service);
        return ok;
    }

    private boolean verifyField(ThriftType t)
    {
        ThriftProtocolType proto = t.getProtocolType();
        if (proto == ThriftProtocolType.SET || proto == ThriftProtocolType.LIST) {
            return verifyField(t.getValueType());
        } else if (proto == ThriftProtocolType.MAP) {
            // no short-circuit
            return verifyField(t.getKeyType()) & verifyField(t.getValueType());
        } else {
            if (knownTypes.contains(t)) {
                return true;
            }
            
            if (includeMap.containsKey(t)) {
                usedIncludedTypes.add(t);
                return true;
            }
            return false;
        }
    }

    private boolean verifyStruct(ThriftType t, boolean quiet)
    {
        ThriftStructMetadata<?> metadata = t.getStructMetadata();
        boolean ok = true;
        for (ThriftFieldMetadata fieldMetadata: metadata.getFields(FieldType.THRIFT_FIELD)) {
            boolean fieldOk = verifyField(fieldMetadata.getThriftType());
            if (!fieldOk) {
                ok = false;
                if (!quiet) {
                    LOG.error("Unknown type {} in {}.{}",
                              thriftTypeRenderer.toString(fieldMetadata.getThriftType()),
                              metadata.getStructName(),
                              fieldMetadata.getName());
                }
            }
        }
        // add t even if it failed verification to avoid spurious errors for types that depend on t
        knownTypes.add(t);
        return ok;
    }

    private Class<?> load(String className)
    {
        className = getFullClassName(className);
        try {
            Class<?> cls = getClassLoader().loadClass(className);
            return cls;
        } catch (ClassNotFoundException e) {
            LOG.warn("Couldn't load class {}", className);
        }
        return null;
    }

    // returns ThriftType, ThriftServiceMetadata or null
    private Object convertToThrift(Class<?> cls)
    {
        Set<ThriftService> serviceAnnotations = ReflectionHelper.getEffectiveClassAnnotations(cls, ThriftService.class);
        if (!serviceAnnotations.isEmpty()) {
            // it's a service
            ThriftServiceMetadata serviceMetadata = new ThriftServiceMetadata(cls, codecManager.getCatalog());
            if (verbose) {
                LOG.info("Found thrift service: {}", cls.getSimpleName());
            }
            return serviceMetadata;
        } else {
            // it's a type (will throw if it's not)
            ThriftType thriftType = codecManager.getCatalog().getThriftType(cls);
            if (verbose) {
                LOG.info("Found thrift type: {}", thriftTypeRenderer.toString(thriftType));
            }
            return thriftType;
        }
    }

    private void gen() throws IOException 
    {
        ImmutableMap.Builder<ThriftType, String> typenameMap = ImmutableMap.builder();
        ImmutableMap.Builder<ThriftServiceMetadata, String> serviceMap = ImmutableMap.builder();
        ImmutableSet.Builder<String> includes = ImmutableSet.builder();
        for (ThriftType t: usedIncludedTypes) {
            String filename = includeMap.get(t);
            includes.add(filename);
            typenameMap.put(t, Files.getNameWithoutExtension(filename));
        }
        
        for (ThriftServiceMetadata s: usedIncludedServices) {
            String filename = includeMap.get(s);
            includes.add(filename);
            serviceMap.put(s, Files.getNameWithoutExtension(filename));
        }
        
        this.thriftTypeRenderer = new ThriftTypeRenderer(typenameMap.build());
        ThriftServiceMetadataRenderer serviceRenderer = new ThriftServiceMetadataRenderer(serviceMap.build());
        TemplateLoader tl = new TemplateLoader(ImmutableList.of("thrift/common.st"),
                ImmutableMap.of(ThriftType.class, thriftTypeRenderer, ThriftServiceMetadata.class, serviceRenderer));
        ThriftContext ctx = new ThriftContext(packageName, ImmutableList.copyOf(includes.build()), thriftTypes, thriftServices, namespaceMap);
        ST template = tl.load("thriftfile");
        template.add("context", ctx);
        template.write(new AutoIndentWriter(outputStreamWriter));
        outputStreamWriter.flush();
    }

    private ClassLoader getClassLoader()
    {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader != null) {
            return classLoader;
        }
        return ClassLoader.getSystemClassLoader();
    }
}
