package com.alanbeidanci.app.data

import androidx.room.withTransaction

data class DualTestBatch(val checkpoint: DualTestCheckpoint, val words: List<WordEntity>)
data class DualTestAnswerResult(val accepted: Boolean, val next: DualTestCheckpoint?)

class DualTestSessions(private val db: AlanDatabase, private val repository: LearningRepository) {
    private val dao = db.dualTestSessionDao()

    suspend fun load(settings: UserSettings, kind: MainlineTestKind): DualTestBatch? = db.withTransaction {
        val key = DualTestCheckpoint.key(kind, settings)
        val stored = dao.get(key)?.checkpoint()
        val checkpoint = stored ?: repository.getDualTestWords(settings, kind).takeIf { it.isNotEmpty() }
            ?.let { words -> DualTestCheckpoint(key, kind, words.map { it.id }).also { dao.save(it.entity()) } }
            ?: return@withTransaction null
        val words = db.wordDao().getByIds(checkpoint.wordIds).associateBy { it.id }
        require(checkpoint.wordIds.all { it in words }) { "双测断点中的词条不存在" }
        DualTestBatch(checkpoint, checkpoint.wordIds.map { words.getValue(it) })
    }

    suspend fun prepare(checkpoint: DualTestCheckpoint): DualTestCheckpoint = db.withTransaction {
        val current = dao.get(checkpoint.key)?.checkpoint() ?: error("双测断点已结束，请重新进入")
        require(current.sessionId == checkpoint.sessionId && current.round == checkpoint.round && current.index == checkpoint.index)
        if (current.preparedAt > 0) return@withTransaction current
        val access = repository.prepareRetrieval(current.wordId, if (current.round == 0) "LISTEN" else "RECALL")
        current.copy(revision = current.revision + 1, preparedAt = access.preparedAt, exposed = access.exposed)
            .also { dao.save(it.entity()) }
    }

    /** Old background/close callbacks cannot resurrect a completed or restored batch. */
    suspend fun save(checkpoint: DualTestCheckpoint) = db.withTransaction {
        require(checkpoint.valid())
        val current = dao.get(checkpoint.key)?.checkpoint() ?: return@withTransaction
        if (current.sessionId != checkpoint.sessionId || current.revision >= checkpoint.revision ||
            current.round != checkpoint.round || current.index != checkpoint.index ||
            current.listening != checkpoint.listening || current.spelling != checkpoint.spelling) return@withTransaction
        dao.save(checkpoint.entity())
    }

    suspend fun answer(checkpoint: DualTestCheckpoint): DualTestAnswerResult = db.withTransaction {
        require(checkpoint.valid() && checkpoint.holdResult)
        val current = dao.get(checkpoint.key)?.checkpoint() ?: return@withTransaction DualTestAnswerResult(false, null)
        if (current.sessionId != checkpoint.sessionId || current.round != checkpoint.round || current.index != checkpoint.index ||
            current.listening != checkpoint.listening || current.spelling != checkpoint.spelling ||
            current.preparedAt != checkpoint.preparedAt || current.exposed != checkpoint.exposed)
            return@withTransaction DualTestAnswerResult(false, current)
        val word = db.wordDao().getById(checkpoint.wordId) ?: error("双测词条不存在")
        val answer = LearningContentPresentation.canonicalSpelling(word.word).filter { it.isLetterOrDigit() }
        val correct = checkpoint.input.isNotEmpty() && checkpoint.input.equals(answer, ignoreCase = true)
        require(correct == checkpoint.holdCorrect) { "双测结果与输入不一致" }
        repository.recordDualAnswer(checkpoint.wordId, correct,
            if (checkpoint.round == 0) QuestionType.LISTENING else QuestionType.SPELLING,
            if (checkpoint.round == 0) correct else checkpoint.listening.getValue(checkpoint.wordId),
            if (checkpoint.round == 1) correct else null, checkpoint.kind,
            if (correct) "exact" else "unknown", checkpoint.exposed, checkpoint.answeredAt)
        val next = checkpoint.afterAnswer(correct)
        if (next == null) dao.delete(checkpoint.key) else dao.save(next.entity())
        DualTestAnswerResult(true, next)
    }
}
