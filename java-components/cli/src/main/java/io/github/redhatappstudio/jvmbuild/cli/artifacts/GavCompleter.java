package io.github.redhatappstudio.jvmbuild.cli.artifacts;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.redhatappstudio.jvmbuild.cli.RequestScopedCompleter;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;

/**
 * Completer that selects artifacts by GAV
 */
public class GavCompleter extends RequestScopedCompleter {

    @Override
    protected Iterable<String> completionCandidates(KubernetesClient client) {
        return client.resources(ArtifactBuild.class).list().getItems().stream().map(s -> s.getSpec().getGav())
                .collect(Collectors.toList());
    }

    public static Map<String, ArtifactBuild> createNames() {
        try (InstanceHandle<KubernetesClient> instanceHandle = Arc.container().instance(KubernetesClient.class)) {
            KubernetesClient client = instanceHandle.get();
            return client.resources(ArtifactBuild.class)
                    .list()
                    .getItems()
                    .stream()
                    .collect(Collectors.toMap(x -> x.getSpec().getGav(), Function.identity(),
                            (k1, k2) -> k1, TreeMap::new));
        }
    }
}
