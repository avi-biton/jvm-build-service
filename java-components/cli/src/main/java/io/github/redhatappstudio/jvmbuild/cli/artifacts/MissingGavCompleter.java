package io.github.redhatappstudio.jvmbuild.cli.artifacts;

import java.util.stream.Collectors;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.redhatappstudio.jvmbuild.cli.RequestScopedCompleter;

/**
 * Completer that selects artifacts by GAV, but only in state missing
 */
public class MissingGavCompleter extends RequestScopedCompleter {

    @Override
    protected Iterable<String> completionCandidates(KubernetesClient client) {
        return client.resources(ArtifactBuild.class).list().getItems().stream()
                .filter(s -> s.getStatus().getState().equals(ArtifactBuild.MISSING)).map(s -> s.getSpec().getGav())
                .collect(Collectors.toList());
    }
}
