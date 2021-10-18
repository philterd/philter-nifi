/*
 * Copyright 2021 Mountain Fog, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mtnfog.philter.processors;

import com.mtnfog.philter.PhilterClient;
import com.mtnfog.philter.model.FilterResponse;
import com.mtnfog.philter.util.UnsafeOkHttpClient;
import okhttp3.OkHttpClient;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.ReadsAttributes;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.*;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Tags({"philter", "phi", "pii", "nppi", "redact", "redaction", "filter", "randomize", "anonymize", "api"})
@CapabilityDescription("Identifies and removes sensitive information in text using Philter.")
@SeeAlso({})
@ReadsAttributes({
        @ReadsAttribute(attribute = Philter.ATTRIBUTE_CONTEXT, description = "The document context."),
        @ReadsAttribute(attribute = Philter.ATTRIBUTE_DOCUMENT_ID, description = "The document ID.")
})
@WritesAttributes({
        @WritesAttribute(attribute = Philter.ATTRIBUTE_DOCUMENT_ID, description = "The document ID when assigned by Philter."),
})
public class Philter extends AbstractProcessor {

    public static final PropertyDescriptor FILTER_PROFILE_NAME = new PropertyDescriptor.Builder()
            .name("Filter Profile Name")
            .description("The name of the filter profile to use for filtering.")
            .defaultValue(null)
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor PHILTER_API_ENDPOINT = new PropertyDescriptor.Builder()
            .name("Philter API Endpoint")
            .description("The endpoint of the Philter API.")
            .defaultValue("http://localhost:8080/")
            .addValidator(StandardValidators.URL_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .required(false)
            .build();

    public static final PropertyDescriptor DISABLE_CERTIFICATE_VALIDATION = new PropertyDescriptor.Builder()
            .name("Ignore self-signed certificates")
            .description("Whether or not to disable certification validation of certificates used by Philter's API.")
            .defaultValue("false")
            .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
            .required(true)
            .build();

    public static final PropertyDescriptor MIME_TYPE = new PropertyDescriptor.Builder()
            .name("MIME Type of Content")
            .description("The mime type of the content.")
            .defaultValue("text/plain")
            .allowableValues("text/plain")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .build();

    public static final Relationship REL_REDACTED = new Relationship.Builder()
            .name("redacted")
            .description("The redacted flowfile will be routed to this transition.")
            .build();

    public static final Relationship REL_ORIGINAL = new Relationship.Builder()
            .name("original")
            .description("The original flowfile will be routed to this transition.")
            .build();

    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("Any flowfiles that fail processing will be routed to this transition.")
            .build();

    private List<PropertyDescriptor> descriptors;
    private Set<Relationship> relationships;

    private PhilterClient philterClient;

    public static final String ATTRIBUTE_CONTEXT = "philter.context";
    public static final String ATTRIBUTE_DOCUMENT_ID = "philter.document.id";

    @Override
    protected void init(final ProcessorInitializationContext context) {

        final List<PropertyDescriptor> descriptors = new ArrayList<>();

        descriptors.add(FILTER_PROFILE_NAME);
        descriptors.add(PHILTER_API_ENDPOINT);
        descriptors.add(DISABLE_CERTIFICATE_VALIDATION);
        descriptors.add(MIME_TYPE);

        this.descriptors = Collections.unmodifiableList(descriptors);

        final Set<Relationship> relationships = new HashSet<>();

        relationships.add(REL_REDACTED);
        relationships.add(REL_ORIGINAL);
        relationships.add(REL_FAILURE);

        this.relationships = Collections.unmodifiableSet(relationships);

    }

    @Override
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) throws Exception {

        final String philterApiEndpoint = context.getProperty(PHILTER_API_ENDPOINT).evaluateAttributeExpressions().getValue();
        final boolean disableCertificateValidation = context.getProperty(DISABLE_CERTIFICATE_VALIDATION).asBoolean();

        if(disableCertificateValidation) {

            final OkHttpClient okHttpClient = UnsafeOkHttpClient.getUnsafeOkHttpClient();

            this.philterClient = new PhilterClient.PhilterClientBuilder()
                    .withEndpoint(philterApiEndpoint)
                    .withOkHttpClientBuilder(okHttpClient.newBuilder())
                    .withTimeout(300)
                    .build();

        } else {

            this.philterClient = new PhilterClient.PhilterClientBuilder()
                    .withEndpoint(philterApiEndpoint)
                    .withTimeout(300)
                    .build();

        }

    }

    @Override
    public void onTrigger(final ProcessContext processContext, final ProcessSession session) throws ProcessException {

        FlowFile originalFlowFile = session.get();

        if (originalFlowFile == null) {
            return;
        }

        final ComponentLog logger = getLogger();

        try {

            // Will hold the content (text we are processing).
            final AtomicReference<String> content = new AtomicReference<>();

            // Read the content of the flowfile.
            session.read(originalFlowFile, in -> content.set(IOUtils.toString(in, Charset.defaultCharset())));

            // Read properties from the processor.
            final String filterProfile = processContext.getProperty(FILTER_PROFILE_NAME).evaluateAttributeExpressions().getValue();
            final String mimeType = processContext.getProperty(MIME_TYPE).evaluateAttributeExpressions().getValue();

            // Read attributes.
            final String context = originalFlowFile.getAttribute(ATTRIBUTE_CONTEXT);
            final String documentId = originalFlowFile.getAttribute(ATTRIBUTE_DOCUMENT_ID);

            // Do the filtering by calling Philter with the appropriate MIME type.
            final FilterResponse filterResponse;

            if(StringUtils.equalsIgnoreCase("text/plain", mimeType)) {
                filterResponse = philterClient.filter(context, documentId, filterProfile, content.get());
            } else {
                // Try to parse it as text/plain but this should never happen.
                filterResponse = philterClient.filter(context, documentId, filterProfile, content.get());
            }

            // Clone the flowfile.
            FlowFile filteredFlowFile = session.create(originalFlowFile);

            // Write the filtered text back to the flowfile.
            filteredFlowFile = session.write(filteredFlowFile, out -> out.write(filterResponse.getFilteredText().getBytes()));

            // Write the document ID and context as attributes.
            filteredFlowFile = session.putAttribute(filteredFlowFile, ATTRIBUTE_DOCUMENT_ID, filterResponse.getDocumentId());
            filteredFlowFile = session.putAttribute(filteredFlowFile, ATTRIBUTE_CONTEXT, context);

            // All done.
            session.transfer(filteredFlowFile, REL_REDACTED);
            session.transfer(originalFlowFile, REL_ORIGINAL);

        } catch (final IOException ex) {

            originalFlowFile = session.penalize(originalFlowFile);
            session.transfer(originalFlowFile, REL_FAILURE);
            logger.error("Unable to process flow file content for Philter redaction.", ex);

        }

    }

}