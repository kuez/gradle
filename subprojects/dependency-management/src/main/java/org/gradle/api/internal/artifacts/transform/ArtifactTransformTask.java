/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.api.internal.artifacts.transform;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import org.gradle.api.Action;
import org.gradle.api.Buildable;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.NonNullApi;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.internal.artifacts.DefaultResolvedArtifact;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactVisitor;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.BuildDependenciesVisitor;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvableArtifact;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet;
import org.gradle.api.internal.attributes.AttributeContainerInternal;
import org.gradle.api.internal.tasks.TaskDependencyContainer;
import org.gradle.api.internal.tasks.TaskDependencyResolveContext;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.internal.component.local.model.ComponentFileArtifactIdentifier;
import org.gradle.internal.component.model.DefaultIvyArtifactName;
import org.gradle.internal.component.model.IvyArtifactName;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.BuildOperationQueue;
import org.gradle.internal.operations.RunnableBuildOperation;

import javax.inject.Inject;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@NonNullApi
public class ArtifactTransformTask extends DefaultTask {

    private final ArtifactTransformer transform;
    private final ResolvedArtifactSet delegate;
    private final AttributeContainerInternal attributes;
    private ConcurrentHashMap<ResolvableArtifact, TransformationResult> artifactResults;
    private ConcurrentHashMap<File, TransformationResult> fileResults;
    private final ArtifactTransformTask requiredTransform;

    @Inject
    public ArtifactTransformTask(UserCodeBackedTransformer transform, ResolvedArtifactSet delegate, AttributeContainerInternal attributes, Optional<ArtifactTransformTask> requiredTransform) {
        this.transform = transform;
        this.delegate = delegate;
        this.attributes = attributes;
        this.requiredTransform = requiredTransform.orNull();
        if (requiredTransform.isPresent()) {
            dependsOn(requiredTransform.get());
        } else {
            dependsOn(new TaskDependencyContainer() {
                @Override
                public void visitDependencies(final TaskDependencyResolveContext context) {
                    ArtifactTransformTask.this.delegate.collectBuildDependencies(new BuildDependenciesVisitor() {
                        @Override
                        public void visitDependency(Object dep) {
                            context.add(dep);
                        }

                        @Override
                        public void visitFailure(Throwable failure) {
                            throw new GradleException("Broken!");
                        }
                    });
                }
            });
        }
    }

    private ResolvedArtifactSet.Completion result;

    @Internal
    public ResolvedArtifactSet.Completion getResult() {
        return result;
    }

    @Internal
    public ResolvedArtifactSet.Completion getResolvedArtifacts() {
        if (requiredTransform != null) {
            return requiredTransform.getResolvedArtifacts();
        }
        ResolveArtifacts resolveArtifacts = new ResolveArtifacts(delegate);
        getBuildOperationExecuter().runAll(resolveArtifacts);

        return resolveArtifacts.getResult();
    }

    public TransformationResult getIncomingTransformationResult(ResolvableArtifact artifact) {
        if (requiredTransform != null) {
            return requiredTransform.artifactResults.get(artifact);
        }
        return new TransformationResult(ImmutableList.of(artifact.getFile()));
    }

    public TransformationResult getIncomingTransformationResult(File file) {
        if (requiredTransform != null) {
            return requiredTransform.fileResults.get(file);
        }
        return new TransformationResult(ImmutableList.of(file));
    }

    @TaskAction
    public void transformArtifacts() {
        artifactResults = new ConcurrentHashMap<ResolvableArtifact, TransformationResult>();
        fileResults = new ConcurrentHashMap<File, TransformationResult>();
        ResolvedArtifactSet.Completion resolvedArtifacts = getResolvedArtifacts();
        resolvedArtifacts.visit(new ArtifactVisitor() {
            @Override
            public void visitArtifact(String variantName, AttributeContainer variantAttributes, ResolvableArtifact artifact) {
                TransformationResult incoming = getIncomingTransformationResult(artifact);
                artifactResults.put(artifact, transform(incoming));
            }

            @Override
            public void visitFile(ComponentArtifactIdentifier artifactIdentifier, String variantName, AttributeContainer variantAttributes, File file) {
                fileResults.put(file, transform(getIncomingTransformationResult(file)));
            }

            private TransformationResult transform(File file) {
                try {
                    List<File> result = transform.transform(file);
                    return new TransformationResult(result);
                } catch (Throwable e) {
                    return new TransformationResult(e);
                }
            }

            private TransformationResult transform(TransformationResult incoming) {
                if (incoming.isFailed()) {
                    return incoming;
                }
                ImmutableList.Builder<File> builder = ImmutableList.builder();
                for (File file : incoming.getTransformedFiles()) {
                    TransformationResult transformationResult = transform(file);
                    if (transformationResult.isFailed()) {
                        return transformationResult;
                    }
                    builder.addAll(transformationResult.getTransformedFiles());
                }
                return new TransformationResult(builder.build());
            }

            @Override
            public boolean requireArtifactFiles() {
                return true;
            }

            @Override
            public boolean includeFiles() {
                return true;
            }

            @Override
            public void visitFailure(Throwable failure) {

            }
        });
        result = new TransformingResult(resolvedArtifacts, artifactResults, fileResults, attributes);
    }

    private static class ResolveArtifacts implements Action<BuildOperationQueue<RunnableBuildOperation>> {

        private final ResolvedArtifactSet delegate;
        private ResolvedArtifactSet.Completion result;

        public ResolveArtifacts(ResolvedArtifactSet delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(BuildOperationQueue<RunnableBuildOperation> actions) {
            result = delegate.startVisit(actions, new NoOpAsyncArtifactListener());
        }

        public ResolvedArtifactSet.Completion getResult() {
            return result;
        }
    }

    @Inject
    public BuildOperationExecutor getBuildOperationExecuter() {
        throw new UnsupportedOperationException();
    }

    public static class TransformingResult implements ResolvedArtifactSet.Completion {
        private final ResolvedArtifactSet.Completion result;
        private final Map<ResolvableArtifact, TransformationResult> artifactResults;
        private final Map<File, TransformationResult> fileResults;
        private final AttributeContainerInternal attributes;

        public TransformingResult(ResolvedArtifactSet.Completion result, Map<ResolvableArtifact, TransformationResult> artifactResults, Map<File, TransformationResult> fileResults, AttributeContainerInternal attributes) {
            this.result = result;
            this.artifactResults = artifactResults;
            this.fileResults = fileResults;
            this.attributes = attributes;
        }

        @Override
        public void visit(ArtifactVisitor visitor) {
            result.visit(new ArtifactTransformingVisitor(visitor, attributes, artifactResults, fileResults));
        }
    }

    private static class NoOpAsyncArtifactListener implements ResolvedArtifactSet.AsyncArtifactListener {
        @Override
        public void artifactAvailable(ResolvableArtifact artifact) {
        }

        @Override
        public boolean requireArtifactFiles() {
            return true;
        }

        @Override
        public boolean includeFileDependencies() {
            return true;
        }

        @Override
        public void fileAvailable(File file) {
        }
    }

    private static class TransformationResult {
        private final List<File> transformedFiles;
        private Throwable failure;

        public TransformationResult(List<File> transformedFiles) {
            this.transformedFiles = transformedFiles;
            this.failure = null;
        }

        public TransformationResult(Throwable failure) {
            this.transformedFiles = null;
            this.failure = failure;
        }

        public boolean isFailed() {
            return failure != null;
        }

        public List<File> getTransformedFiles() {
            return Preconditions.checkNotNull(transformedFiles);
        }

        public Throwable getFailure() {
            return Preconditions.checkNotNull(failure);
        }
    }


    private static class ArtifactTransformingVisitor implements ArtifactVisitor {
        private final ArtifactVisitor visitor;
        private final AttributeContainerInternal target;
        private final Map<ResolvableArtifact, TransformationResult> artifactResults;
        private final Map<File, TransformationResult> fileResults;

        ArtifactTransformingVisitor(ArtifactVisitor visitor, AttributeContainerInternal target, Map<ResolvableArtifact, TransformationResult> artifactResults, Map<File, TransformationResult> fileResults) {
            this.visitor = visitor;
            this.target = target;
            this.artifactResults = artifactResults;
            this.fileResults = fileResults;
        }

        @Override
        public void visitArtifact(String variantName, AttributeContainer variantAttributes, ResolvableArtifact artifact) {
            TransformationResult result = artifactResults.get(artifact);
            if (result.isFailed()) {
                visitor.visitFailure(result.getFailure());
                return;
            }

            ResolvedArtifact sourceArtifact = artifact.toPublicView();
            List<File> transformedFiles = result.getTransformedFiles();
            TaskDependency buildDependencies = ((Buildable) artifact).getBuildDependencies();

            for (File output : transformedFiles) {
                IvyArtifactName artifactName = DefaultIvyArtifactName.forFile(output, sourceArtifact.getClassifier());
                ComponentArtifactIdentifier newId = new ComponentFileArtifactIdentifier(sourceArtifact.getId().getComponentIdentifier(), artifactName);
                DefaultResolvedArtifact resolvedArtifact = new DefaultResolvedArtifact(sourceArtifact.getModuleVersion().getId(), artifactName, newId, buildDependencies, output);
                visitor.visitArtifact(variantName, target, resolvedArtifact);
            }
        }

        @Override
        public void visitFailure(Throwable failure) {
            visitor.visitFailure(failure);
        }

        @Override
        public boolean includeFiles() {
            return visitor.includeFiles();
        }

        @Override
        public boolean requireArtifactFiles() {
            return visitor.requireArtifactFiles();
        }

        @Override
        public void visitFile(ComponentArtifactIdentifier artifactIdentifier, String variantName, AttributeContainer variantAttributes, File file) {
            TransformationResult result = fileResults.get(file);
            if (result.isFailed()) {
                visitor.visitFailure(result.getFailure());
                return;
            }

            List<File> transformedFiles = result.getTransformedFiles();
            for (File outputFile : transformedFiles) {
                visitor.visitFile(new ComponentFileArtifactIdentifier(artifactIdentifier.getComponentIdentifier(), outputFile.getName()), variantName, target, outputFile);
            }
        }
    }
}