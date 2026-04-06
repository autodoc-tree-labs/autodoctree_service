package com.autodoctree.api.controller.question

import com.autodoctree.api.domain.question.QuestionService
import com.autodoctree.api.tenant.WorkspaceContextResolver
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/questions")
class QuestionController(
    private val questionService: QuestionService,
    private val workspaceContextResolver: WorkspaceContextResolver
) {

    @GetMapping
    fun listQuestions(
        request: HttpServletRequest,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "20") limit: Int
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return questionService.listQuestions(context, status, limit)
    }

    @PostMapping("/{questionId}/answer")
    fun answerQuestion(
        request: HttpServletRequest,
        @PathVariable questionId: String,
        @Valid @RequestBody body: AnswerQuestionRequest
    ): Map<String, Any?> {
        val context = workspaceContextResolver.resolve(request)
        return questionService.answerQuestion(context, questionId, body.answer)
    }
}

data class AnswerQuestionRequest(
    @field:NotBlank val answer: String
)
