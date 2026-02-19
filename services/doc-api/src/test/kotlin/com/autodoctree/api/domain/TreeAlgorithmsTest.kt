package com.autodoctree.api.domain

import com.autodoctree.api.config.TreeProperties
import com.autodoctree.api.config.FeatureFlags
import com.autodoctree.api.db.DocumentRow
import com.autodoctree.api.db.EmbeddingRow
import com.autodoctree.api.db.FeedbackEventRow
import com.autodoctree.api.db.TreeNodeRow
import com.autodoctree.api.reranker.PairRerankerClient
import com.autodoctree.api.reranker.RerankerPairInput
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TreeAlgorithmsTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `neighbor builder returns bounded topK adjacency`() {
        val registry = SimpleMeterRegistry()
        val builder = NeighborBuilder(objectMapper, testLabeler(), registry)
        val docs = listOf(
            doc("doc-a", "alpha"),
            doc("doc-b", "beta"),
            doc("doc-c", "gamma")
        )
        val embeddings = mapOf(
            "doc-a" to embedding("doc-a", listOf(1.0, 0.0)),
            "doc-b" to embedding("doc-b", listOf(0.9, 0.1)),
            "doc-c" to embedding("doc-c", listOf(0.0, 1.0))
        )

        val graph = builder.build("ws-a", docs, embeddings, topK = 1, minSimilarity = 0.0)

        assertEquals(3, graph.adjacency.size)
        assertTrue(graph.adjacency.values.all { it.size <= 1 })
    }

    @Test
    fun `neighbor builder drops links below min similarity`() {
        val registry = SimpleMeterRegistry()
        val builder = NeighborBuilder(objectMapper, testLabeler(), registry)
        val docs = listOf(
            doc("doc-a", "alpha"),
            doc("doc-b", "beta"),
            doc("doc-c", "gamma")
        )
        val embeddings = mapOf(
            "doc-a" to embedding("doc-a", listOf(1.0, 0.0)),
            "doc-b" to embedding("doc-b", listOf(0.9, 0.1)),
            "doc-c" to embedding("doc-c", listOf(0.0, 1.0))
        )

        val graph = builder.build("ws-a", docs, embeddings, topK = 3, minSimilarity = 0.9)

        assertEquals(listOf("doc-b"), graph.adjacency["doc-a"]?.map { it.documentId })
        assertEquals(listOf("doc-a"), graph.adjacency["doc-b"]?.map { it.documentId })
        assertTrue(graph.adjacency["doc-c"].isNullOrEmpty())
    }

    @Test
    fun `neighbor builder uses lexical fallback for local stub embeddings`() {
        val registry = SimpleMeterRegistry()
        val builder = NeighborBuilder(objectMapper, testLabeler(), registry)
        val docs = listOf(
            doc("doc-a", "사회 연구"),
            doc("doc-b", "과학 연구"),
            doc("doc-c", "축구 경기")
        )
        val embeddings = mapOf(
            "doc-a" to embedding("doc-a", listOf(0.1, 0.9), modelVersion = "local-stub-v1"),
            "doc-b" to embedding("doc-b", listOf(0.9, 0.1), modelVersion = "local-stub-v1"),
            "doc-c" to embedding("doc-c", listOf(0.2, 0.8), modelVersion = "local-stub-v1")
        )

        val graph = builder.build("ws-a", docs, embeddings, topK = 2, minSimilarity = 0.2)

        assertTrue(graph.adjacency["doc-a"].orEmpty().any { it.documentId == "doc-b" })
        assertFalse(graph.adjacency["doc-a"].orEmpty().any { it.documentId == "doc-c" })
    }

    @Test
    fun `neighbor builder unifies korean particle variants`() {
        val registry = SimpleMeterRegistry()
        val builder = NeighborBuilder(objectMapper, testLabeler(), registry)
        val docs = listOf(
            doc("doc-a", "섹스"),
            doc("doc-b", "섹스와 성"),
            doc("doc-c", "축구 경기")
        )
        val embeddings = mapOf(
            "doc-a" to embedding("doc-a", listOf(0.1, 0.9), modelVersion = "local-stub-v1"),
            "doc-b" to embedding("doc-b", listOf(0.9, 0.1), modelVersion = "local-stub-v1"),
            "doc-c" to embedding("doc-c", listOf(0.2, 0.8), modelVersion = "local-stub-v1")
        )

        val graph = builder.build("ws-a", docs, embeddings, topK = 2, minSimilarity = 0.25)

        assertTrue(graph.adjacency["doc-a"].orEmpty().any { it.documentId == "doc-b" })
        assertFalse(graph.adjacency["doc-a"].orEmpty().any { it.documentId == "doc-c" })
    }

    @Test
    fun `neighbor builder lexical gate uses body overlap and bm25 lite`() {
        val registry = SimpleMeterRegistry()
        val builder = NeighborBuilder(objectMapper, testLabeler(), registry)
        val docs = listOf(
            doc("doc-a", "alpha", "회계 승인 회계 승인 예산 검토"),
            doc("doc-b", "beta", "회계 승인 결산 보고 승인"),
            doc("doc-c", "gamma", "축구 경기 일정 하이라이트")
        )
        val embeddings = mapOf(
            "doc-a" to embedding("doc-a", listOf(1.0, 0.0)),
            "doc-b" to embedding("doc-b", listOf(0.95, 0.05)),
            "doc-c" to embedding("doc-c", listOf(0.0, 1.0))
        )

        val graph = builder.build(
            workspaceId = "ws-a",
            documents = docs,
            embeddings = embeddings,
            topK = 2,
            minSimilarity = 0.0,
            semanticWeight = 0.6,
            lexicalWeight = 0.4,
            lexicalGate = 0.2,
            mutualKnnRequired = false,
            sharedNeighborJaccardMin = 0.0
        )

        val link = graph.adjacency["doc-a"].orEmpty().firstOrNull { it.documentId == "doc-b" }
            ?: error("doc-a to doc-b link missing")
        assertEquals(0, link.sharedEntityCount)
        assertEquals(0, link.titleOverlap)
        assertTrue(link.lexicalGatePassed)
        assertEquals("EMBEDDING_LEXICAL_GATED", link.reason)
    }

    @Test
    fun `neighbor builder enforces mutual knn when enabled`() {
        val registry = SimpleMeterRegistry()
        val builder = NeighborBuilder(objectMapper, testLabeler(), registry)
        val docs = listOf(
            doc("doc-a", "alpha billing"),
            doc("doc-b", "alpha billing invoice"),
            doc("doc-c", "invoice note")
        )
        val embeddings = mapOf(
            "doc-a" to embedding("doc-a", listOf(1.0, 0.0)),
            "doc-b" to embedding("doc-b", listOf(0.95, 0.05)),
            "doc-c" to embedding("doc-c", listOf(0.3, 0.7))
        )

        val strict = builder.build(
            workspaceId = "ws-a",
            documents = docs,
            embeddings = embeddings,
            topK = 1,
            minSimilarity = 0.0,
            mutualKnnRequired = true,
            sharedNeighborJaccardMin = 0.0
        )
        val relaxed = builder.build(
            workspaceId = "ws-a",
            documents = docs,
            embeddings = embeddings,
            topK = 1,
            minSimilarity = 0.0,
            mutualKnnRequired = false,
            sharedNeighborJaccardMin = 0.0
        )

        assertTrue(strict.adjacency["doc-c"].isNullOrEmpty())
        assertFalse(relaxed.adjacency["doc-c"].isNullOrEmpty())
    }

    @Test
    fun `normalize with low min similarity overconnects graph`() {
        // 설명: normalize=true일 때 0.25 임계값은 무관 baseline(~0.5)까지 통과시켜 과연결을 만들 수 있다.
        val registry = SimpleMeterRegistry()
        val builder = NeighborBuilder(objectMapper, testLabeler(), registry)
        val docs = listOf(
            doc("a-1", "finance alpha"),
            doc("a-2", "finance beta"),
            doc("a-3", "finance gamma"),
            doc("a-4", "finance delta"),
            doc("b-1", "history alpha"),
            doc("b-2", "history beta"),
            doc("b-3", "history gamma"),
            doc("b-4", "history delta")
        )
        val embeddings = mapOf(
            "a-1" to embedding("a-1", listOf(1.0, 0.0)),
            "a-2" to embedding("a-2", listOf(0.99, 0.01)),
            "a-3" to embedding("a-3", listOf(0.98, 0.02)),
            "a-4" to embedding("a-4", listOf(0.97, 0.03)),
            "b-1" to embedding("b-1", listOf(0.0, 1.0)),
            "b-2" to embedding("b-2", listOf(0.01, 0.99)),
            "b-3" to embedding("b-3", listOf(0.02, 0.98)),
            "b-4" to embedding("b-4", listOf(0.03, 0.97))
        )
        val clusterer = testClusterer(
            treeProperties = testTreeProperties().copy(maxClusterSize = 20, minClusterSize = 2),
            featureFlags = testFeatureFlags().copy(communityClustering = false)
        )

        val graphLow = builder.build(
            workspaceId = "ws-a",
            documents = docs,
            embeddings = embeddings,
            topK = 4,
            edgeBudget = 4,
            minSimilarity = 0.25,
            normalize = true,
            mutualKnnRequired = false,
            sharedNeighborJaccardMin = 0.0,
            degreeCap = 100
        )
        val lowClusters = clusterer.cluster(docs, graphLow, maxClusterSize = 20)

        assertEquals(1, lowClusters.size)
        assertTrue(graphLow.stats.edgeCount >= docs.size * 4)
        assertTrue(graphLow.stats.degreeStats.p99 >= 4.0)
    }

    @Test
    fun `raising normalized min similarity separates communities`() {
        // 설명: 같은 입력에서 minSimilarity를 0.65로 올리면 cross-topic edge가 줄고 군집이 분리된다.
        val registry = SimpleMeterRegistry()
        val builder = NeighborBuilder(objectMapper, testLabeler(), registry)
        val docs = listOf(
            doc("a-1", "finance alpha"),
            doc("a-2", "finance beta"),
            doc("a-3", "finance gamma"),
            doc("a-4", "finance delta"),
            doc("b-1", "history alpha"),
            doc("b-2", "history beta"),
            doc("b-3", "history gamma"),
            doc("b-4", "history delta")
        )
        val embeddings = mapOf(
            "a-1" to embedding("a-1", listOf(1.0, 0.0)),
            "a-2" to embedding("a-2", listOf(0.99, 0.01)),
            "a-3" to embedding("a-3", listOf(0.98, 0.02)),
            "a-4" to embedding("a-4", listOf(0.97, 0.03)),
            "b-1" to embedding("b-1", listOf(0.0, 1.0)),
            "b-2" to embedding("b-2", listOf(0.01, 0.99)),
            "b-3" to embedding("b-3", listOf(0.02, 0.98)),
            "b-4" to embedding("b-4", listOf(0.03, 0.97))
        )
        val clusterer = testClusterer(
            treeProperties = testTreeProperties().copy(maxClusterSize = 20, minClusterSize = 2),
            featureFlags = testFeatureFlags().copy(communityClustering = false)
        )

        val graphLow = builder.build(
            workspaceId = "ws-a",
            documents = docs,
            embeddings = embeddings,
            topK = 4,
            edgeBudget = 4,
            minSimilarity = 0.25,
            normalize = true,
            mutualKnnRequired = false,
            sharedNeighborJaccardMin = 0.0,
            degreeCap = 100
        )
        val graphHigh = builder.build(
            workspaceId = "ws-a",
            documents = docs,
            embeddings = embeddings,
            topK = 4,
            edgeBudget = 4,
            minSimilarity = 0.65,
            normalize = true,
            mutualKnnRequired = false,
            sharedNeighborJaccardMin = 0.0,
            degreeCap = 100
        )
        val highClusters = clusterer.cluster(docs, graphHigh, maxClusterSize = 20)

        assertEquals(2, highClusters.size)
        assertTrue(graphHigh.stats.edgeCount < graphLow.stats.edgeCount)
        assertTrue(graphHigh.stats.degreeStats.p99 < graphLow.stats.degreeStats.p99)
    }

    @Test
    fun `shared-neighbor jaccard prunes bridge edges`() {
        // 설명: topK=1에서는 공유 이웃이 없는 링크의 Jaccard가 0이므로 SNN threshold로 브릿지를 제거할 수 있다.
        val registry = SimpleMeterRegistry()
        val builder = NeighborBuilder(objectMapper, testLabeler(), registry)
        val docs = listOf(
            doc("doc-a", "alpha topic"),
            doc("doc-b", "alpha topic detail"),
            doc("doc-c", "orthogonal topic")
        )
        val embeddings = mapOf(
            "doc-a" to embedding("doc-a", listOf(1.0, 0.0)),
            "doc-b" to embedding("doc-b", listOf(0.9, 0.1)),
            "doc-c" to embedding("doc-c", listOf(0.0, 1.0))
        )

        val withoutSnn = builder.build(
            workspaceId = "ws-a",
            documents = docs,
            embeddings = embeddings,
            topK = 1,
            edgeBudget = 1,
            minSimilarity = 0.0,
            mutualKnnRequired = false,
            sharedNeighborJaccardMin = 0.0
        )
        val withSnn = builder.build(
            workspaceId = "ws-a",
            documents = docs,
            embeddings = embeddings,
            topK = 1,
            edgeBudget = 1,
            minSimilarity = 0.0,
            mutualKnnRequired = false,
            sharedNeighborJaccardMin = 0.10
        )

        assertTrue(withoutSnn.adjacency["doc-a"].orEmpty().any { it.documentId == "doc-b" })
        assertTrue(withoutSnn.adjacency["doc-b"].orEmpty().any { it.documentId == "doc-a" })
        assertFalse(withSnn.adjacency["doc-a"].orEmpty().any { it.documentId == "doc-b" })
        assertFalse(withSnn.adjacency["doc-b"].orEmpty().any { it.documentId == "doc-a" })
    }

    @Test
    fun `neighbor builder emits finite instrumentation stats`() {
        // 설명: 리빌드 요약 계측값은 최소 케이스에서도 0/NaN-only가 아니어야 한다.
        val registry = SimpleMeterRegistry()
        val builder = NeighborBuilder(objectMapper, testLabeler(), registry)
        val docs = listOf(
            doc("doc-a", "finance invoice"),
            doc("doc-b", "finance billing"),
            doc("doc-c", "sports match")
        )
        val embeddings = mapOf(
            "doc-a" to embedding("doc-a", listOf(1.0, 0.0)),
            "doc-b" to embedding("doc-b", listOf(0.95, 0.05)),
            "doc-c" to embedding("doc-c", listOf(0.0, 1.0))
        )

        val graph = builder.build(
            workspaceId = "ws-a",
            documents = docs,
            embeddings = embeddings,
            topK = 2,
            minSimilarity = 0.0,
            mutualKnnRequired = false,
            sharedNeighborJaccardMin = 0.0
        )

        assertTrue(graph.stats.similarityDistributions.semantic.count > 0)
        assertTrue(graph.stats.similarityDistributions.fused.count > 0)
        assertTrue(graph.stats.similarityDistributions.semantic.p95.isFinite())
        assertTrue(graph.stats.similarityDistributions.fused.mean.isFinite())
        assertTrue(graph.stats.edgeFilterStats.evaluatedPairs > 0)
        assertTrue(graph.stats.edgeFilterStats.edgesBeforeFilter > 0)
    }

    @Test
    fun `neighbor builder applies per-node edge budget`() {
        val registry = SimpleMeterRegistry()
        val builder = NeighborBuilder(objectMapper, testLabeler(), registry)
        val docs = listOf(
            doc("doc-a", "alpha"),
            doc("doc-b", "alpha"),
            doc("doc-c", "alpha"),
            doc("doc-d", "alpha")
        )
        val embeddings = mapOf(
            "doc-a" to embedding("doc-a", listOf(1.0, 0.0)),
            "doc-b" to embedding("doc-b", listOf(0.98, 0.02)),
            "doc-c" to embedding("doc-c", listOf(0.96, 0.04)),
            "doc-d" to embedding("doc-d", listOf(0.94, 0.06))
        )

        val graph = builder.build(
            workspaceId = "ws-a",
            documents = docs,
            embeddings = embeddings,
            topK = 3,
            minSimilarity = 0.0,
            edgeBudget = 1,
            sharedNeighborJaccardMin = 0.0
        )

        assertTrue(graph.adjacency.values.all { it.size <= 1 })
    }

    @Test
    fun `neighbor builder stage b reranker filters low confidence pair`() {
        val registry = SimpleMeterRegistry()
        val rerankerClient = object : PairRerankerClient {
            override fun scorePairs(workspaceId: String, pairs: List<RerankerPairInput>): Map<String, Double> {
                return pairs.associate { pair ->
                    val score = if (pair.pairKey == "doc-a::doc-b") 0.18 else 0.92
                    pair.pairKey to score
                }
            }
        }
        val builder = NeighborBuilder(objectMapper, testLabeler(), registry, rerankerClient)
        val docs = listOf(
            doc("doc-a", "alpha finance"),
            doc("doc-b", "alpha invoice"),
            doc("doc-c", "alpha billing")
        )
        val embeddings = mapOf(
            "doc-a" to embedding("doc-a", listOf(1.0, 0.0)),
            "doc-b" to embedding("doc-b", listOf(0.98, 0.02)),
            "doc-c" to embedding("doc-c", listOf(0.96, 0.04))
        )
        val textByDoc = docs.associate { doc ->
            doc.id to "${doc.title} ${doc.bodyText.orEmpty()}"
        }

        val graph = builder.build(
            workspaceId = "ws-a",
            documents = docs,
            embeddings = embeddings,
            topK = 2,
            minSimilarity = 0.0,
            mutualKnnRequired = false,
            sharedNeighborJaccardMin = 0.0,
            rerankerEnabled = true,
            rerankerPerDocBudget = 2,
            rerankerPassThreshold = 0.5,
            rerankerTextByDocumentId = textByDoc
        )

        assertFalse(graph.adjacency["doc-a"].orEmpty().any { it.documentId == "doc-b" })
        assertTrue(graph.adjacency["doc-a"].orEmpty().any { it.documentId == "doc-c" })
        assertTrue(graph.stats.rerankerValidatedPairs > 0)
        assertTrue(graph.stats.rerankerPassRate < 1.0)
    }

    @Test
    fun `neighbor builder reranker fallback keeps original edges`() {
        val registry = SimpleMeterRegistry()
        val failingRerankerClient = object : PairRerankerClient {
            override fun scorePairs(workspaceId: String, pairs: List<RerankerPairInput>): Map<String, Double> {
                throw IllegalStateException("reranker unavailable")
            }
        }
        val builder = NeighborBuilder(objectMapper, testLabeler(), registry, failingRerankerClient)
        val docs = listOf(
            doc("doc-a", "platform roadmap"),
            doc("doc-b", "platform architecture"),
            doc("doc-c", "service reliability")
        )
        val embeddings = mapOf(
            "doc-a" to embedding("doc-a", listOf(1.0, 0.0)),
            "doc-b" to embedding("doc-b", listOf(0.94, 0.06)),
            "doc-c" to embedding("doc-c", listOf(0.88, 0.12))
        )
        val textByDoc = docs.associate { doc ->
            doc.id to "${doc.title} ${doc.bodyText.orEmpty()}"
        }

        val graph = builder.build(
            workspaceId = "ws-a",
            documents = docs,
            embeddings = embeddings,
            topK = 2,
            minSimilarity = 0.0,
            mutualKnnRequired = false,
            sharedNeighborJaccardMin = 0.0,
            rerankerEnabled = true,
            rerankerPerDocBudget = 2,
            rerankerPassThreshold = 0.5,
            rerankerTextByDocumentId = textByDoc
        )

        assertTrue(graph.adjacency["doc-a"].orEmpty().isNotEmpty())
        assertEquals(1.0, graph.stats.rerankerFallbackRate)
    }

    @Test
    fun `cluster merge affinity keeps singleton when no strong target exists`() {
        // 설명: small cluster의 max affinity가 threshold 미만이면 강제 병합하지 않고 singleton으로 유지한다.
        val clusterer = testClusterer(
            treeProperties = testTreeProperties().copy(
                minClusterSize = 2,
                clusterMergeMinAffinity = 0.55,
                maxClusterSize = 10
            ),
            featureFlags = testFeatureFlags().copy(communityClustering = false)
        )
        val docs = listOf(
            doc("a-1", "finance-a"),
            doc("a-2", "finance-b"),
            doc("b-1", "history-a"),
            doc("b-2", "history-b"),
            doc("s-1", "singleton")
        )
        val graph = NeighborGraph(
            adjacency = mapOf(
                "a-1" to listOf(NeighborLink("a-2", 0.92)),
                "a-2" to listOf(NeighborLink("a-1", 0.92)),
                "b-1" to listOf(NeighborLink("b-2", 0.91)),
                "b-2" to listOf(NeighborLink("b-1", 0.91)),
                "s-1" to emptyList()
            )
        )

        val result = clusterer.clusterWithStats(docs, graph, maxClusterSize = 10)

        assertTrue(result.clusters.any { it.documentIds.size == 1 && it.documentIds.contains("s-1") })
        assertEquals(1, result.stats.mergeAttempted)
        assertEquals(0, result.stats.merged)
        assertEquals(1, result.stats.keptSingleton)
    }

    @Test
    fun `clusterer splits oversized component`() {
        val clusterer = testClusterer(SimpleMeterRegistry())
        val docs = (1..9).map { index -> doc("doc-$index", "document $index") }
        val adjacency = docs.associate { d ->
            d.id to docs.filter { it.id != d.id }.map { NeighborLink(it.id, 0.7) }
        }

        val clusters = clusterer.cluster(docs, NeighborGraph(adjacency), maxClusterSize = 4)

        assertTrue(clusters.size >= 3)
        assertTrue(clusters.all { it.documentIds.size <= 4 })
    }

    @Test
    fun `consensus clusterer is reproducible and keeps strong pairs`() {
        val registry = SimpleMeterRegistry()
        val clusterer = TreeClusterer(
            treeProperties = testTreeProperties().copy(
                consensusEnabled = true,
                consensusThreshold = 0.67
            ),
            featureFlags = testFeatureFlags(),
            meterRegistry = registry
        )
        val docs = listOf(
            doc("f-1", "finance invoice"),
            doc("f-2", "finance settlement"),
            doc("s-1", "sports match"),
            doc("s-2", "sports league"),
            doc("l-1", "legal policy"),
            doc("l-2", "legal contract")
        )
        val adjacency = mapOf(
            "f-1" to listOf(NeighborLink("f-2", 0.93), NeighborLink("s-1", 0.08)),
            "f-2" to listOf(NeighborLink("f-1", 0.93), NeighborLink("l-1", 0.07)),
            "s-1" to listOf(NeighborLink("s-2", 0.94), NeighborLink("f-1", 0.08)),
            "s-2" to listOf(NeighborLink("s-1", 0.94), NeighborLink("l-2", 0.06)),
            "l-1" to listOf(NeighborLink("l-2", 0.92), NeighborLink("f-2", 0.07)),
            "l-2" to listOf(NeighborLink("l-1", 0.92), NeighborLink("s-2", 0.06))
        )

        val first = clusterer.cluster(docs, NeighborGraph(adjacency), maxClusterSize = 6)
        val second = clusterer.cluster(docs, NeighborGraph(adjacency), maxClusterSize = 6)
        val assignmentFirst = first.flatMap { cluster -> cluster.documentIds.map { it to cluster.id } }.toMap()
        val assignmentSecond = second.flatMap { cluster -> cluster.documentIds.map { it to cluster.id } }.toMap()

        assertEquals(assignmentFirst, assignmentSecond)
        assertEquals(docs.size, assignmentFirst.size)
        assertTrue(first.isNotEmpty())
    }

    @Test
    fun `labeler returns non empty labels`() {
        val labeler = testLabeler()
        val docs = listOf(
            doc("doc-a", "billing invoice payment"),
            doc("doc-b", "billing settlement payment"),
            doc("doc-c", "engineering architecture service")
        )
        val clusters = listOf(
            TreeCluster("cluster-1", listOf("doc-a", "doc-b")),
            TreeCluster("cluster-2", listOf("doc-c"))
        )

        val labels = labeler.labelClusters(docs, clusters)

        assertEquals(2, labels.size)
        assertTrue(labels.values.all { it.isNotBlank() })
        assertTrue(labels.values.all { it.length <= 48 })
    }

    @Test
    fun `labeler keeps labels conservative for mixed clusters`() {
        val labeler = testLabeler()
        val docs = listOf(
            doc("doc-a", "사회 연구"),
            doc("doc-b", "과학 연구"),
            doc("doc-c", "문학 연구")
        )
        val clusters = listOf(TreeCluster("cluster-1", docs.map { it.id }))

        val labels = labeler.labelClusters(docs, clusters)

        val label = labels["cluster-1"] ?: ""
        assertTrue(label.contains("연구"))
        assertFalse(label.contains("--"))
        assertTrue(label.length <= 20)
    }

    @Test
    fun `labeler removes numeric noise from singleton labels`() {
        val labeler = testLabeler()
        val docs = listOf(doc("doc-a", "runtime 1770532490 open"))
        val clusters = listOf(TreeCluster("cluster-1", listOf("doc-a")))

        val label = labeler.labelClusters(docs, clusters)["cluster-1"] ?: ""

        assertFalse(label.contains("1770532490"))
        assertFalse(label.contains("--"))
    }

    @Test
    fun `labeler tokenizes korean text`() {
        val labeler = testLabeler()

        val tokens = labeler.tokenize("사회 연구 문서 자동 분류 테스트")

        assertTrue(tokens.contains("사회"))
        assertTrue(tokens.contains("연구"))
        assertTrue(tokens.contains("자동"))
        assertFalse(tokens.contains("문서"))
        assertFalse(tokens.contains("테스트"))
    }

    @Test
    fun `labeler normalizes korean particles`() {
        val labeler = testLabeler()

        val tokens = labeler.tokenize("섹스와 과학은 연구를 다룹니다")

        assertTrue(tokens.contains("섹스"))
        assertFalse(tokens.contains("섹스와"))
        assertTrue(tokens.contains("과학"))
        assertFalse(tokens.contains("과학은"))
        assertTrue(tokens.contains("연구"))
        assertFalse(tokens.contains("연구를"))
    }

    @Test
    fun `fallback tokenizer creates ngram candidates`() {
        val tokenizer = FallbackTokenizer()

        val tokens = tokenizer.tokenize("과학 연구 자동 분류")

        assertTrue(tokens.contains("과학-연구"))
        assertTrue(tokens.contains("연구-자동"))
        assertTrue(tokens.contains("과학-연구-자동"))
    }

    @Test
    fun `labeler filters forbidden terms from cluster label`() {
        val labeler = testLabeler()
        val docs = listOf(
            doc("doc-a", "섹스 관련 자료"),
            doc("doc-b", "섹스 연구 노트")
        )
        val clusters = listOf(TreeCluster("cluster-1", docs.map { it.id }))

        val label = labeler.labelClusters(docs, clusters).getValue("cluster-1")

        assertFalse(label.contains("섹스"))
        assertTrue(label.isNotBlank())
    }

    @Test
    fun `labeler merges similar labels`() {
        val labeler = testLabeler()

        val mapping = labeler.mergeSimilarLabels(listOf("연구", "리서치", "연구-문학"))

        assertEquals(mapping["연구"], mapping["리서치"])
        assertNotNull(mapping["연구-문학"])
    }

    @Test
    fun `personalization model prefers moved label for similar text`() {
        val labeler = testLabeler()
        val engine = TreePersonalizationEngine(
            objectMapper = objectMapper,
            treeProperties = testTreeProperties()
        )

        val docs = listOf(
            doc("doc-a", "billing invoice reconciliation"),
            doc("doc-b", "billing invoice for march")
        )
        val node = TreeNodeRow(
            id = "node-1",
            workspaceId = "ws-a",
            snapshotId = "snap-1",
            viewType = "TOPIC",
            parentId = "root",
            label = "billing",
            depth = 2,
            locked = false,
            createdAt = LocalDateTime.now()
        )
        val event = FeedbackEventRow(
            id = "evt-1",
            workspaceId = "ws-a",
            userId = "u-1",
            eventType = "MOVE",
            payloadJson = objectMapper.writeValueAsString(
                mapOf(
                    "document_id" to "doc-a",
                    "to_node_id" to "node-1"
                )
            ),
            createdAt = LocalDateTime.now()
        )

        val model = engine.buildModel(
            feedbackEvents = listOf(event),
            activeNodes = listOf(node),
            documents = docs,
            tokenizer = labeler::tokenize
        )

        val preferred = model.preferredLabelFor(docs[1], labeler::tokenize)

        assertNotNull(preferred)
        assertEquals("billing", preferred)
        assertFalse(model.hasSignalFor("missing-doc"))
    }

    @Test
    fun `personalization routing v2 propagates by embedding neighborhood`() {
        val labeler = testLabeler()
        val engine = TreePersonalizationEngine(
            objectMapper = objectMapper,
            treeProperties = testTreeProperties()
        )
        val docs = listOf(
            doc("doc-a", "finance invoice settlement"),
            doc("doc-b", "invoice settlement monthly"),
            doc("doc-c", "football score highlights")
        )
        val node = TreeNodeRow(
            id = "node-1",
            workspaceId = "ws-a",
            snapshotId = "snap-1",
            viewType = "TOPIC",
            parentId = "root",
            label = "finance",
            depth = 2,
            locked = false,
            createdAt = LocalDateTime.now()
        )
        val moveEvent = FeedbackEventRow(
            id = "evt-1",
            workspaceId = "ws-a",
            userId = "u-1",
            eventType = "MOVE",
            payloadJson = objectMapper.writeValueAsString(
                mapOf(
                    "document_id" to "doc-a",
                    "to_node_id" to "node-1"
                )
            ),
            createdAt = LocalDateTime.now()
        )
        val embeddings = mapOf(
            "doc-a" to embedding("doc-a", listOf(0.95, 0.05, 0.0)),
            "doc-b" to embedding("doc-b", listOf(0.92, 0.08, 0.0)),
            "doc-c" to embedding("doc-c", listOf(0.01, 0.04, 0.95))
        )

        val model = engine.buildModel(
            feedbackEvents = listOf(moveEvent),
            activeNodes = listOf(node),
            documents = docs,
            tokenizer = labeler::tokenize,
            embeddings = embeddings,
            routingV2Enabled = true
        )

        assertEquals("finance", model.preferredLabelFor(docs[1], labeler::tokenize))
    }

    private fun testLabeler(registry: SimpleMeterRegistry = SimpleMeterRegistry()): TreeLabeler {
        return TreeLabeler(
            tokenizer = FallbackTokenizer(),
            featureFlags = testFeatureFlags(),
            meterRegistry = registry
        )
    }

    private fun testClusterer(
        registry: SimpleMeterRegistry = SimpleMeterRegistry(),
        treeProperties: TreeProperties = testTreeProperties(),
        featureFlags: FeatureFlags = testFeatureFlags()
    ): TreeClusterer {
        return TreeClusterer(
            treeProperties = treeProperties,
            featureFlags = featureFlags,
            meterRegistry = registry
        )
    }

    private fun testTreeProperties(): TreeProperties {
        return TreeProperties(
            neighborTopK = 3,
            neighborMinSimilarity = 0.0,
            neighborNormalize = true,
            maxClusterSize = 10,
            minClusterSize = 2,
            communityResolution = 1.0,
            personalizationDecay = 0.9,
            personalizationMinScore = 0.2,
            fusionSemanticWeight = 0.8,
            fusionLexicalWeight = 0.2,
            fusionLexicalGate = 0.35,
            otherClusterScoreThreshold = 0.32
        )
    }

    private fun testFeatureFlags(): FeatureFlags {
        return FeatureFlags(
            autoTree = true,
            explain = true,
            hybridSearch = false,
            embeddingOllama = false,
            labelQualityFilter = true,
            communityClustering = true,
            noriTokenizer = false,
            feedbackRoutingV2 = true,
            userRulesV1 = false,
            adminTreeDebug = true
        )
    }

    private fun doc(id: String, title: String, body: String = title): DocumentRow {
        val now = LocalDateTime.now()
        return DocumentRow(
            id = id,
            workspaceId = "ws-a",
            title = title,
            bodyMarkdown = body,
            bodyText = body,
            sourceType = "EDITOR",
            status = "READY",
            version = 1,
            deleted = false,
            createdBy = "u-1",
            createdAt = now,
            updatedAt = now
        )
    }

    private fun embedding(documentId: String, vector: List<Double>, modelVersion: String = "test-model-v1"): EmbeddingRow {
        return EmbeddingRow(
            id = "emb-$documentId",
            workspaceId = "ws-a",
            documentId = documentId,
            targetType = "DOCUMENT",
            targetId = documentId,
            inputHash = "hash-$documentId-$modelVersion",
            vectorJson = objectMapper.writeValueAsString(vector),
            modelVersion = modelVersion,
            createdAt = LocalDateTime.now()
        )
    }
}
