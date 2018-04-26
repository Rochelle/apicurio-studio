/*
 * Copyright 2018 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.apicurio.hub.api.codegen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.lang.model.element.Modifier;

import org.apache.commons.io.IOUtils;
import org.jsonschema2pojo.DefaultGenerationConfig;
import org.jsonschema2pojo.GenerationConfig;
import org.jsonschema2pojo.Jackson2Annotator;
import org.jsonschema2pojo.SchemaGenerator;
import org.jsonschema2pojo.SchemaMapper;
import org.jsonschema2pojo.SchemaStore;
import org.jsonschema2pojo.rules.RuleFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;
import com.sun.codemodel.JCodeModel;

import io.apicurio.hub.api.codegen.beans.CodegenInfo;
import io.apicurio.hub.api.codegen.beans.CodegenJavaArgument;
import io.apicurio.hub.api.codegen.beans.CodegenJavaBean;
import io.apicurio.hub.api.codegen.beans.CodegenJavaInterface;
import io.apicurio.hub.api.codegen.beans.CodegenJavaMethod;
import io.apicurio.hub.api.codegen.js.CodegenExecutor;
import io.apicurio.hub.api.codegen.util.IndexedCodeWriter;


/**
 * Class used to generate a WildFly Swarm JAX-RS project from an OpenAPI document.
 * 
 * @author eric.wittmann@gmail.com
 */
public class OpenApi2Swarm {

    private static ObjectMapper mapper = new ObjectMapper();

    private String openApiDoc;
    private SwarmProjectSettings settings;
    
    /**
     * Constructor.
     */
    public OpenApi2Swarm() {
        this.settings = new SwarmProjectSettings();
        this.settings.artifactId = "generated-api";
        this.settings.groupId = "org.example.api";
        this.settings.javaPackage = "org.example.api";
    }

    /**
     * Configure the settings.
     * @param settings
     */
    public void setSettings(SwarmProjectSettings settings) {
        this.settings = settings;
    }

    /**
     * Sets the OpenAPI document.
     * @param content
     * @throws IOException
     */
    public void setOpenApiDocument(String content) {
        this.openApiDoc = content;
    }

    /**
     * Sets the OpenAPI document via a URL to the content.
     * @param url
     * @throws IOException
     */
    public void setOpenApiDocument(URL url) throws IOException {
        try (InputStream is = url.openStream()) {
            this.setOpenApiDocument(is);
        }
    }
    
    /**
     * Sets the OpenAPI document via an input stream.
     * @param stream
     * @throws IOException
     */
    public void setOpenApiDocument(InputStream stream) throws IOException {
        this.openApiDoc = IOUtils.toString(stream);
    }
    
    /**
     * Generate the WildFly Swarm project.
     * @throws IOException
     */
    public ByteArrayOutputStream generate() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        
        CodegenInfo info = getInfoFromApiDoc();
        
        try (ZipOutputStream zos = new ZipOutputStream(output)) {
            zos.putNextEntry(new ZipEntry(this.settings.artifactId + "/pom.xml"));
            zos.write(generatePomXml(info).getBytes());
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry(this.settings.artifactId + "/src/main/resources/META-INF/openapi.json"));
            zos.write(this.openApiDoc.getBytes());
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry(this.settings.artifactId + "/src/main/resources/META-INF/microprofile-config.properties"));
            zos.write(generateMicroprofileConfigProperties().getBytes());
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry(this.settings.artifactId + javaPackageToZipPath(this.settings.javaPackage) + "JaxRsApplication.java"));
            zos.write(generateJaxRsApplication().getBytes());
            zos.closeEntry();
            
            for (CodegenJavaInterface iface : info.getInterfaces()) {
                String javaInterface = generateJavaInterface(iface);
                zos.putNextEntry(new ZipEntry(this.settings.artifactId + javaPackageToZipPath(iface.getPackage()) + iface.getName() + ".java"));
                zos.write(javaInterface.getBytes());
                zos.closeEntry();
            }
            
            for (CodegenJavaBean bean : info.getBeans()) {
                String javaBean = generateJavaBean(bean);
                zos.putNextEntry(new ZipEntry(this.settings.artifactId + javaPackageToZipPath(bean.getPackage()) + bean.getName() + ".java"));
                zos.write(javaBean.getBytes());
                zos.closeEntry();
            }
        }
        
        return output;
    }
    
    /**
     * Processes the OpenAPI document to produce a CodegenInfo object that contains everything
     * needed to generate appropriate Java class(es).
     */
    protected CodegenInfo getInfoFromApiDoc() throws IOException {
        String codegenJson = processApiDoc();
        return mapper.readerFor(CodegenInfo.class).readValue(codegenJson);
    }

    /**
     * Processes the OAI document and produces a -codegen.json file that can be
     * deserialized into a {@link CodegenInfo} object.
     */
    protected String processApiDoc() throws IOException {
        try {
            return CodegenExecutor.executeCodegen(openApiDoc, this.settings.javaPackage);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /**
     * Generates the pom.xml file.
     * @param info 
     */
    private String generatePomXml(CodegenInfo info) throws IOException {
        String template = IOUtils.toString(getClass().getResource("pom.xml"));
        return template.replace("$GROUP_ID$", this.settings.groupId)
                .replace("$ARTIFACT_ID$", this.settings.artifactId)
                .replace("$VERSION$", info.getVersion())
                .replace("$NAME$", info.getName())
                .replace("$DESCRIPTION$", info.getDescription());
    }

    /**
     * Generates the microprofile-config.properties file to include in the generated project.
     */
    private String generateMicroprofileConfigProperties() throws IOException {
        String template = IOUtils.toString(getClass().getResource("microprofile-config.properties"));
        return template;
    }

    /**
     * Generates the JaxRsApplication java class.
     */
    private String generateJaxRsApplication() throws IOException {
        TypeSpec jaxRsApp = TypeSpec.classBuilder(ClassName.get(this.settings.javaPackage, "JaxRsApplication"))
                .addModifiers(Modifier.PUBLIC)
                .superclass(ClassName.get("javax.ws.rs.core", "Application"))
                .addAnnotation(ClassName.get("javax.enterprise.context", "ApplicationScoped"))
                .addAnnotation(AnnotationSpec.builder(ClassName.get("javax.ws.rs", "ApplicationPath"))
                        .addMember("value", "$S", "/")
                        .build())
                .addJavadoc("The JAX-RS application.\n")
                .build();
        JavaFile javaFile = JavaFile.builder(this.settings.javaPackage, jaxRsApp).build();
        return javaFile.toString();
    }

    /**
     * Generates a Jax-rs interface from the given codegen information.
     * @param _interface
     */
    private String generateJavaInterface(CodegenJavaInterface _interface) {
        // Create the JAX-RS interface spec itself.
        Builder interfaceBuilder = TypeSpec
                .interfaceBuilder(ClassName.get(_interface.getPackage(), _interface.getName()));
        interfaceBuilder.addModifiers(Modifier.PUBLIC)
                .addAnnotation(ClassName.get("javax.enterprise.context", "ApplicationScoped"))
                .addAnnotation(AnnotationSpec.builder(ClassName.get("javax.ws.rs", "Path"))
                        .addMember("value", "$S", _interface.getPath()).build())
                .addJavadoc("A JAX-RS interface.  An implementation of this interface must be provided.\n");

        // Add specs for all the methods.
        for (CodegenJavaMethod cgMethod : _interface.getMethods()) {
            com.squareup.javapoet.MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(cgMethod.getName());
            methodBuilder.addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT);
            // The @Path annotation.
            if (cgMethod.getPath() != null) {
                methodBuilder.addAnnotation(AnnotationSpec.builder(ClassName.get("javax.ws.rs", "Path"))
                        .addMember("value", "$S", cgMethod.getPath()).build());
            }
            // The @GET, @PUT, @POST, etc annotation
            methodBuilder.addAnnotation(AnnotationSpec.builder(ClassName.get("javax.ws.rs", cgMethod.getMethod().toUpperCase())).build());
            // The @Produces annotation
            if (cgMethod.getProduces() != null && !cgMethod.getProduces().isEmpty()) {
                methodBuilder.addAnnotation(AnnotationSpec.builder(ClassName.get("javax.ws.rs", "Produces"))
                        .addMember("value", "$L", toStringArrayLiteral(cgMethod.getProduces())).build());
            }
            // The @Consumes annotation
            if (cgMethod.getConsumes() != null && !cgMethod.getConsumes().isEmpty()) {
                methodBuilder.addAnnotation(AnnotationSpec.builder(ClassName.get("javax.ws.rs", "Consumes"))
                        .addMember("value", "$L", toStringArrayLiteral(cgMethod.getConsumes())).build());
            }
            // The method return type.
            if (cgMethod.getReturn() != null) {
                TypeName returnType = generateTypeName(cgMethod.getReturn().getCollection(),
                        cgMethod.getReturn().getType(), cgMethod.getReturn().getFormat(), true,
                        ClassName.get("javax.ws.rs.core", "Response"));
                methodBuilder.returns(returnType);
            }
            
            // The method arguments.
            if (cgMethod.getArguments() != null && !cgMethod.getArguments().isEmpty()) {
                for (CodegenJavaArgument cgArgument : cgMethod.getArguments()) {
                    TypeName defaultParamType = ClassName.OBJECT;
                    if (cgArgument.getIn().equals("body")) {
                        defaultParamType = ClassName.get("javax.ws.rs.core", "Response");
                    }
                    TypeName paramType = generateTypeName(cgArgument.getCollection(), cgArgument.getType(),
                            cgArgument.getFormat(), cgArgument.getRequired(), defaultParamType);
                    com.squareup.javapoet.ParameterSpec.Builder paramBuilder = ParameterSpec.builder(paramType, cgArgument.getName());
                    if (cgArgument.getIn().equals("path")) {
                        paramBuilder.addAnnotation(AnnotationSpec.builder(ClassName.get("javax.ws.rs", "PathParam"))
                                .addMember("value", "$S", cgArgument.getName()).build());
                    }
                    if (cgArgument.getIn().equals("query")) {
                        paramBuilder.addAnnotation(AnnotationSpec.builder(ClassName.get("javax.ws.rs", "QueryParam"))
                                .addMember("value", "$S", cgArgument.getName()).build());
                    }
                    methodBuilder.addParameter(paramBuilder.build());
                }
            }
            
            // Javadoc
            if (cgMethod.getDescription() != null) {
                methodBuilder.addJavadoc(cgMethod.getDescription());
                methodBuilder.addJavadoc("\n");
            }
            
            interfaceBuilder.addMethod(methodBuilder.build());
        }
        
        TypeSpec jaxRsInterface = interfaceBuilder.build();
        
        JavaFile javaFile = JavaFile.builder(_interface.getPackage(), jaxRsInterface).build();
        return javaFile.toString();
    }

    /**
     * Generates the java type name for a collection (optional) and type.  Examples include list/string, 
     * null/org.example.Bean, list/org.example.OtherBean, etc.
     * @param collection
     * @param type
     * @param format
     * @param required
     * @param defaultType
     */
    private TypeName generateTypeName(String collection, String type, String format, boolean required,
            TypeName defaultType) {
        if (type == null) {
            return defaultType;
        }
        
        TypeName coreType = null;
        if (type.equals("string")) {
            coreType = ClassName.get(String.class);
            if (format != null) {
                if (format.equals("date") || format.equals("date-time")) {
                    coreType = ClassName.get(Date.class);
                }
                // TODO handle byte, binary
            }
        } else if (type.equals("integer")) {
            coreType = ClassName.get(Integer.class);
            if (format != null) {
                if (format.equals("int32")) {
                    coreType = required ? TypeName.INT : ClassName.get(Integer.class);
                } else if (format.equals("int64")) {
                    coreType = required ? TypeName.LONG : ClassName.get(Long.class);
                }
            }
        } else if (type.equals("number")) {
            coreType = ClassName.get(Number.class);
            if (format != null) {
                if (format.equals("float")) {
                    coreType = required ? TypeName.FLOAT : ClassName.get(Float.class);
                } else if (format.equals("double")) {
                    coreType = required ? TypeName.DOUBLE : ClassName.get(Double.class);
                }
            }
        } else if (type.equals("boolean")) {
            coreType = ClassName.get(Boolean.class);
        } else {
            try {
                coreType = ClassName.bestGuess(type);
            } catch (Exception e) {
                return defaultType;
            }
        }
        
        if (collection == null) {
            return coreType;
        }
        
        if ("list".equals(collection)) {
            return ParameterizedTypeName.get(ClassName.get(List.class), coreType);
        }

        return defaultType;
    }

    /**
     * Converts a set of strings into an array literal format.
     * @param values
     */
    private static String toStringArrayLiteral(Set<String> values) {
        StringBuilder builder = new StringBuilder();

        if (values.size() == 1) {
            builder.append("\"");
            builder.append(values.iterator().next().replace("\"", "\\\""));
            builder.append("\"");
        } else {
            builder.append("{");
            boolean first = true;
            for (String value : values) {
                if (!first) {
                    builder.append(", ");
                }
                builder.append("\"");
                builder.append(value.replace("\"", "\\\""));
                builder.append("\"");
                first = false;
            }
            builder.append("}");
        }
        return builder.toString();
    }

    /**
     * Generates a Java Bean class for the given bean info.  The bean info should
     * have a name, package, and JSON Schema.  This information will be used to 
     * generate a POJO.
     * @param bean
     * @throws IOException
     */
    private String generateJavaBean(CodegenJavaBean bean) throws IOException {
        JCodeModel codeModel = new JCodeModel();
        GenerationConfig config = new DefaultGenerationConfig() {
            @Override
            public boolean isUsePrimitives() {
                return false;
            }
            @Override
            public boolean isIncludeHashcodeAndEquals() {
                return false;
            }
            @Override
            public boolean isIncludeAdditionalProperties() {
                return false;
            }
            @Override
            public boolean isIncludeToString() {
                return false;
            }
        };

        SchemaMapper schemaMapper = new SchemaMapper(
                new RuleFactory(config, new Jackson2Annotator(config), new SchemaStore()),
                new SchemaGenerator());
        String source = mapper.writeValueAsString(bean.get$schema());
        schemaMapper.generate(codeModel, bean.getName(), bean.getPackage(), source);

        IndexedCodeWriter codeWriter = new IndexedCodeWriter();
        codeModel.build(codeWriter);
        
        return codeWriter.get(bean.getPackage() + "." + bean.getName());
    }

    private static String javaPackageToZipPath(String javaPackage) {
        return "/src/main/java/" + javaPackageToPath(javaPackage);
    }
    
    private static String javaPackageToPath(String javaPackage) {
        return javaPackage.replaceAll("[^A-Za-z0-9.]", "").replace('.', '/') + "/";
    }

    /**
     * Represents some basic meta information about the project being generated.
     * @author eric.wittmann@gmail.com
     */
    public static class SwarmProjectSettings {
        public String groupId;
        public String artifactId;
        public String javaPackage;
    }
    
    public static void main(String[] args) {
        Builder interfaceBuilder = TypeSpec
                .interfaceBuilder(ClassName.get("org.example.foo", "MyInterface"));
        TypeSpec iface = interfaceBuilder.build();
        JavaFile javaFile = JavaFile.builder("org.example.foo", iface).build();
        System.out.println(javaFile.toString());
    }

}
