package com.autodoctree.api.controller.tree

import com.autodoctree.api.domain.tree.TreeService
import com.autodoctree.api.domain.tree.TreeViewType
import com.autodoctree.api.tenant.WorkspaceContextResolver
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/tree")
class TreeController(
    private val treeService: TreeService,
    private val workspaceContextResolver: WorkspaceContextResolver
) {

    @GetMapping("/active")
    fun active(
        request: HttpServletRequest,
        @RequestParam(required = false) view: String?
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return treeService.getActiveTree(context, TreeViewType.fromApi(view))
    }

    @GetMapping("/snapshots")
    fun snapshots(
        request: HttpServletRequest,
        @RequestParam(required = false) view: String?
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return treeService.listSnapshots(context, TreeViewType.fromApi(view))
    }

    @PostMapping("/rebuild")
    fun rebuild(
        request: HttpServletRequest,
        @Valid @RequestBody body: RebuildRequest
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return treeService.requestRebuild(
            context = context,
            mode = body.mode,
            viewType = TreeViewType.fromApi(body.view)
        )
    }

    @GetMapping("/rebuild/status")
    fun rebuildStatus(
        request: HttpServletRequest,
        @RequestParam(required = false) view: String?
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return treeService.getRebuildStatus(context, TreeViewType.fromApi(view))
    }

    @PostMapping("/snapshots/{snapshotId}/activate")
    fun activate(
        request: HttpServletRequest,
        @PathVariable snapshotId: String
    ): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        treeService.activateSnapshot(context, snapshotId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/nodes/{nodeId}/lock")
    fun lockNode(
        request: HttpServletRequest,
        @PathVariable nodeId: String,
        @Valid @RequestBody body: LockNodeRequest
    ): ResponseEntity<Void> {
        val context = workspaceContextResolver.resolve(request)
        treeService.lockNode(context, nodeId, body.locked)
        return ResponseEntity.noContent().build()
    }
}

@RestController
@RequestMapping("/api/v1/trees")
class TreesController(
    private val treeService: TreeService,
    private val workspaceContextResolver: WorkspaceContextResolver
) {

    @GetMapping
    fun treeByView(
        request: HttpServletRequest,
        @RequestParam(required = false) view: String?
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return treeService.getTreeByView(context, TreeViewType.fromApi(view))
    }
}

data class RebuildRequest(
    @field:NotBlank val mode: String = "DEBOUNCED",
    val view: String? = null
)

data class LockNodeRequest(
    @field:NotNull val locked: Boolean
)
