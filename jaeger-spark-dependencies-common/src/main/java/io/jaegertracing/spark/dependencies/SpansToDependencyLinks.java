/**
 * Copyright 2017 The Jaeger Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.jaegertracing.spark.dependencies;

import io.jaegertracing.spark.dependencies.model.Dependency;
import io.jaegertracing.spark.dependencies.model.KeyValue;
import io.jaegertracing.spark.dependencies.model.Reference;
import io.jaegertracing.spark.dependencies.model.Span;
import io.opentracing.tag.Tags;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.spark.api.java.function.Function;

/**
 * @author Pavol Loffay
 */
public class SpansToDependencyLinks implements Function<Iterable<Span>, Iterable<Dependency>>{

    /**
     * Derives dependency links based on supplied spans.
     *
     * @param trace trace
     * @return collection of dependency links, note that it contains duplicates
     * @throws Exception
     */

    public String peerServiceTag = "";

    public SpansToDependencyLinks(String peerServiceTag){
        this.peerServiceTag = peerServiceTag;
    }

    @Override
    public Iterable<Dependency> call(Iterable<Span> trace) {
        Map<Long, Set<Span>> spanMap = new LinkedHashMap<>();
        Map<Long, Set<Span>> spanChildrenMap = new LinkedHashMap<>();
        for (Span span: trace) {
            // Map of children
            for (Reference ref: span.getRefs()){
              Set <Span> children = spanChildrenMap.get(ref.getSpanId());
              if (children == null){
                children = new LinkedHashSet<>();
                spanChildrenMap.put(ref.getSpanId(), children);
              }
              children.add(span);
            }
            // Map of parents
            Set<Span> sharedSpans = spanMap.get(span.getSpanId());
            if (sharedSpans == null) {
                sharedSpans = new LinkedHashSet<>();
                spanMap.put(span.getSpanId(), sharedSpans);
            }
            sharedSpans.add(span);
        }

        // Let's start with zipkin shared spans
        List<Dependency> result = sharedSpanDependencies(spanMap);

        for (Span span: trace) {
            if (span.getRefs() == null || span.getRefs().isEmpty() ||
                span.getProcess() == null || span.getProcess().getServiceName() == null) {
                continue;
            }

            // if the current span is shared and not a client span we skip it
            // because the link from this span to parent should be from client span
            if (spanMap.get(span.getSpanId()).size() > 1 && !isClientSpan(span)) {
                continue;
            }

            for (Reference reference: span.getRefs()) {
                Set<Span> parents = spanMap.get(reference.getSpanId());
                if (parents != null) {
                    if (parents.size() > 1) {
                        serverSpan(parents)
                            .ifPresent(parent ->
                                result.add(new Dependency(parent.getProcess().getServiceName(), span.getProcess().getServiceName()))
                            );
                    } else {
                        // this is jaeger span or zipkin native (not shared!)
                        Span parent = parents.iterator().next();
                        if (parent.getProcess() == null || parent.getProcess().getServiceName() == null) {
                            continue;
                        }
                        result.add(new Dependency(parent.getProcess().getServiceName(), span.getProcess().getServiceName()));
                    }
                }
            }
            // We are on a leaf so we try to add a dependency for calls to components that calls remote components not instrumented
            if (spanChildrenMap.get(span.getSpanId()) == null ){
              String targetName = span.getTag(peerServiceTag);
              if (targetName != null) {
                result.add(new Dependency(span.getProcess().getServiceName(), targetName));
              }
            }
        }
        return result;
    }

    static Optional<Span> serverSpan(Set<Span> sharedSpans) {
        for (Span span: sharedSpans) {
            if (isServerSpan(span)) {
                return Optional.of(span);
            }
        }

        return Optional.empty();
    }

    static boolean isClientSpan(Span span) {
        return Tags.SPAN_KIND_CLIENT.equals(span.getTag(Tags.SPAN_KIND.getKey()));
    }

    static boolean isServerSpan(Span span) {
        return Tags.SPAN_KIND_SERVER.equals(span.getTag(Tags.SPAN_KIND.getKey()));
    }

    private List<Dependency> sharedSpanDependencies(Map<Long, Set<Span>> spanMap) {
        List<Dependency> dependencies = new ArrayList<>();
        // create links between shared spans
        for (Set<Span> sharedSpans: spanMap.values()) {
            sharedSpanDependency(sharedSpans)
                .ifPresent(dependencies::add);
        }
        return dependencies;
    }

    protected Optional<Dependency> sharedSpanDependency(Set<Span> sharedSpans) {
        String clientService = null;
        String serverService = null;
        for (Span span: sharedSpans) {
            for (KeyValue tag: span.getTags()) {
                if (Tags.SPAN_KIND_CLIENT.equals(tag.getValueString()) || Tags.SPAN_KIND_CONSUMER.equals(tag.getValueString())) {
                    clientService = span.getProcess().getServiceName();
                } else if (Tags.SPAN_KIND_SERVER.equals(tag.getValueString()) || Tags.SPAN_KIND_PRODUCER.equals(tag.getValueString())) {
                    serverService = span.getProcess().getServiceName();
                }

                if (clientService != null && serverService != null) {
                    return Optional.of(new Dependency(clientService, serverService));
                }
            }
        }
        return Optional.empty();
    }
}
