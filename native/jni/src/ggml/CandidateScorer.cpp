#include "CandidateScorer.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <utility>

#include "ggml.h"

namespace {
constexpr llama_seq_id kPrefixSequenceId = 0;
constexpr llama_seq_id kCandidateSequenceId = 1;
constexpr int kMaximumCandidatesPerCall = 64;

bool shouldAddBos(const LanguageModel &model, CandidateScorerBosPolicy policy) {
    switch(policy) {
        case CandidateScorerBosPolicy::Always:
            return true;
        case CandidateScorerBosPolicy::Never:
            return false;
        case CandidateScorerBosPolicy::ModelDefault:
            return model.modelAddsBosByDefault();
    }
    return false;
}

void keepLastTokens(token_sequence *tokens, size_t maximumSize) {
    if(tokens->size() <= maximumSize) return;
    tokens->erase(tokens->begin(), tokens->end() - (long)maximumSize);
}
} // namespace

CandidateScorer::CandidateScorer(
        std::unique_ptr<LanguageModel> model,
        CandidateScorerConfig config)
        : mModel(std::move(model)), mConfig(config) {
}

std::unique_ptr<CandidateScorer> CandidateScorer::create(
        const std::string &modelPath,
        const CandidateScorerConfig &config,
        std::string *error) {
    if(config.maxContextTokens < 16 || config.maxContextTokens > LLAMA_CONTEXT_SIZE) {
        if(error != nullptr) *error = "maxContextTokens is outside the supported range.";
        return nullptr;
    }
    if(config.maxBatchSize < 1 || config.maxBatchSize > kMaximumCandidatesPerCall) {
        if(error != nullptr) *error = "maxBatchSize is outside the supported range.";
        return nullptr;
    }
    if(config.addEos && config.supportsRightContext) {
        if(error != nullptr) *error = "EOS scoring cannot be combined with right-context scoring.";
        return nullptr;
    }

    std::unique_ptr<LanguageModel> model(LlamaAdapter::createLanguageModel(modelPath));
    if(model == nullptr) {
        if(error != nullptr) *error = "Could not load GGUF model or tokenizer.";
        return nullptr;
    }

    if(config.bosPolicy == CandidateScorerBosPolicy::Always && model->bosToken() < 0) {
        if(error != nullptr) *error = "The model does not define a BOS token.";
        return nullptr;
    }
    if(config.addEos && model->eosToken() < 0) {
        if(error != nullptr) *error = "The model does not define an EOS token.";
        return nullptr;
    }

    return std::unique_ptr<CandidateScorer>(
            new CandidateScorer(std::move(model), config));
}

CandidateScorerResult CandidateScorer::score(
        const std::string &leftContext,
        const std::string &rightContext,
        const std::vector<CandidateScorerInput> &candidates,
        int rightContextTokenLimit) {
    CandidateScorerResult result;
    const int64_t startMicros = ggml_time_us();

    if(candidates.empty() || candidates.size() > (size_t)mConfig.maxBatchSize ||
            candidates.size() > kMaximumCandidatesPerCall) {
        result.error = "Candidate count exceeds the configured native batch size.";
        return result;
    }
    if(rightContextTokenLimit < 0 || rightContextTokenLimit > 64) {
        result.error = "Right-context token limit is outside the supported range.";
        return result;
    }

    token_sequence rightTokens;
    if(mConfig.supportsRightContext && rightContextTokenLimit > 0 && !rightContext.empty()) {
        rightTokens = mModel->tokenize(rightContext, false, false);
        if(rightTokens.size() > (size_t)rightContextTokenLimit) {
            rightTokens.resize((size_t)rightContextTokenLimit);
        }
    }

    std::vector<token_sequence> candidateTokens;
    candidateTokens.reserve(candidates.size());
    size_t maximumContinuationTokens = rightTokens.size();
    for(const CandidateScorerInput &candidate : candidates) {
        token_sequence tokens = mModel->tokenize(candidate.replacementText, false, false);
        if(tokens.empty()) {
            result.error = "A candidate produced no model tokens.";
            return result;
        }
        if(mConfig.addEos) {
            tokens.push_back(mModel->eosToken());
        }
        maximumContinuationTokens = std::max(
                maximumContinuationTokens,
                tokens.size() + rightTokens.size());
        candidateTokens.push_back(std::move(tokens));
    }

    if(maximumContinuationTokens >= LLAMA_CONTEXT_SIZE) {
        result.error = "Candidate and right context exceed the native model context.";
        return result;
    }

    const bool addBos = shouldAddBos(*mModel, mConfig.bosPolicy);
    token_sequence rawPrefix = mModel->tokenize(leftContext, false, false);
    size_t prefixCapacity = std::min(
            (size_t)mConfig.maxContextTokens,
            (size_t)LLAMA_CONTEXT_SIZE - maximumContinuationTokens);
    if(prefixCapacity == 0) {
        result.error = "No context capacity remains after candidate tokenization.";
        return result;
    }

    const size_t bosTokens = addBos ? 1U : 0U;
    if(prefixCapacity < bosTokens) {
        result.error = "No context capacity remains for the required BOS token.";
        return result;
    }
    const size_t textPrefixCapacity = prefixCapacity - bosTokens;
    if(rawPrefix.size() > textPrefixCapacity) {
        if(mConfig.contextTruncation == CandidateScorerContextTruncation::Reject) {
            result.error = "Left context exceeds the configured token limit.";
            return result;
        }
        keepLastTokens(&rawPrefix, textPrefixCapacity);
    }

    token_sequence prefix;
    prefix.reserve(rawPrefix.size() + bosTokens);
    if(addBos) prefix.push_back(mModel->bosToken());
    prefix.insert(prefix.end(), rawPrefix.begin(), rawPrefix.end());

    if(prefix.empty()) {
        result.error = "An empty context without BOS cannot predict the first candidate token.";
        return result;
    }

    llama_context *context = mModel->context();
    llama_kv_cache_clear(context);
    mModel->transformerContext.active_context.clear();

    std::vector<float> prefixLogits;
    if(!decodePrefix(
            prefix,
            &prefixLogits,
            &result.diagnostics.batchCount,
            &result.error)) {
        llama_kv_cache_clear(context);
        return result;
    }

    result.scores.reserve(candidates.size());
    for(size_t index = 0; index < candidateTokens.size(); index++) {
        llama_kv_cache_seq_cp(
                context,
                kPrefixSequenceId,
                kCandidateSequenceId,
                0,
                (llama_pos)prefix.size());

        CandidateSequenceScore scoreValue;
        if(!decodeAndScoreSequence(
                kCandidateSequenceId,
                (int)prefix.size(),
                candidateTokens[index],
                rightTokens,
                prefixLogits,
                &scoreValue,
                &result.diagnostics.batchCount,
                &result.error)) {
            llama_kv_cache_clear(context);
            return result;
        }
        result.scores.push_back(scoreValue);
        result.diagnostics.evaluatedCandidateTokens += scoreValue.candidateTokenCount;
        result.diagnostics.evaluatedRightContextTokens += scoreValue.rightContextTokenCount;

        llama_kv_cache_seq_rm(context, kCandidateSequenceId, -1, -1);
    }

    result.diagnostics.reusedPrefixTokens =
            (int)(prefix.size() * candidates.size());
    result.diagnostics.elapsedMicros = ggml_time_us() - startMicros;
    result.success = true;

    // Do not leave request-specific state available to later legacy inference calls.
    llama_kv_cache_clear(context);
    mModel->transformerContext.active_context.clear();
    return result;
}

bool CandidateScorer::decodePrefix(
        const token_sequence &tokens,
        std::vector<float> *outLogits,
        int *batchCount,
        std::string *error) {
    llama_context *context = mModel->context();
    llama_batch batch = mModel->adapter->batch;
    const int vocabularySize = llama_n_vocab(mModel->model());
    const int nativeBatchSize = std::max(1, mModel->adapter->n_batch);

    size_t offset = 0;
    while(offset < tokens.size()) {
        const int chunkSize = (int)std::min(
                (size_t)nativeBatchSize,
                tokens.size() - offset);
        const bool finalChunk = offset + (size_t)chunkSize == tokens.size();

        batch.n_tokens = chunkSize;
        for(int index = 0; index < chunkSize; index++) {
            batch.token[index] = tokens[offset + (size_t)index];
            batch.pos[index] = (llama_pos)(offset + (size_t)index);
            batch.seq_id[index][0] = kPrefixSequenceId;
            batch.n_seq_id[index] = 1;
            batch.logits[index] = false;
        }
        if(finalChunk) batch.logits[chunkSize - 1] = true;

        if(llama_decode(context, batch) != 0) {
            if(error != nullptr) *error = "llama_decode failed while evaluating left context.";
            return false;
        }
        if(batchCount != nullptr) *batchCount += 1;

        if(finalChunk) {
            const float *logits = llama_get_logits_ith(context, chunkSize - 1);
            if(logits == nullptr) {
                if(error != nullptr) *error = "No logits were produced for the left context.";
                return false;
            }
            outLogits->assign(logits, logits + vocabularySize);
        }

        offset += (size_t)chunkSize;
    }

    return !outLogits->empty();
}

bool CandidateScorer::decodeAndScoreSequence(
        llama_seq_id sequenceId,
        int startPosition,
        const token_sequence &candidateTokens,
        const token_sequence &rightContextTokens,
        const std::vector<float> &prefixLogits,
        CandidateSequenceScore *score,
        int *batchCount,
        std::string *error) {
    token_sequence combined = candidateTokens;
    combined.insert(combined.end(), rightContextTokens.begin(), rightContextTokens.end());
    if(combined.empty()) {
        if(error != nullptr) *error = "Cannot score an empty token sequence.";
        return false;
    }
    if(startPosition + (int)combined.size() > LLAMA_CONTEXT_SIZE) {
        if(error != nullptr) *error = "Candidate sequence exceeds native context capacity.";
        return false;
    }

    llama_context *context = mModel->context();
    llama_batch batch = mModel->adapter->batch;
    const int vocabularySize = llama_n_vocab(mModel->model());
    const int nativeBatchSize = std::max(1, mModel->adapter->n_batch);
    std::vector<float> currentLogits = prefixLogits;

    size_t offset = 0;
    while(offset < combined.size()) {
        const int chunkSize = (int)std::min(
                (size_t)nativeBatchSize,
                combined.size() - offset);

        batch.n_tokens = chunkSize;
        for(int index = 0; index < chunkSize; index++) {
            batch.token[index] = combined[offset + (size_t)index];
            batch.pos[index] = (llama_pos)(startPosition + (int)offset + index);
            batch.seq_id[index][0] = sequenceId;
            batch.n_seq_id[index] = 1;
            batch.logits[index] = true;
        }

        if(llama_decode(context, batch) != 0) {
            if(error != nullptr) *error = "llama_decode failed while scoring a candidate.";
            return false;
        }
        if(batchCount != nullptr) *batchCount += 1;

        for(int index = 0; index < chunkSize; index++) {
            const size_t globalIndex = offset + (size_t)index;
            const float *predictionLogits = index == 0
                    ? currentLogits.data()
                    : llama_get_logits_ith(context, index - 1);
            const double logProbability = selectedTokenLogProbability(
                    predictionLogits,
                    vocabularySize,
                    combined[globalIndex]);
            if(!std::isfinite(logProbability)) {
                if(error != nullptr) *error = "Model produced a non-finite token probability.";
                return false;
            }

            if(globalIndex < candidateTokens.size()) {
                score->candidateLogProbability += logProbability;
                score->candidateTokenCount += 1;
            } else {
                score->rightContextLogProbability += logProbability;
                score->rightContextTokenCount += 1;
            }
        }

        const float *lastLogits = llama_get_logits_ith(context, chunkSize - 1);
        if(lastLogits == nullptr) {
            if(error != nullptr) *error = "No continuation logits were produced for a candidate.";
            return false;
        }
        currentLogits.assign(lastLogits, lastLogits + vocabularySize);
        offset += (size_t)chunkSize;
    }

    return true;
}

double CandidateScorer::selectedTokenLogProbability(
        const float *logits,
        int vocabularySize,
        llama_token selectedToken) {
    if(logits == nullptr || selectedToken < 0 || selectedToken >= vocabularySize ||
            vocabularySize <= 0) {
        return -std::numeric_limits<double>::infinity();
    }

    double maximum = -std::numeric_limits<double>::infinity();
    for(int index = 0; index < vocabularySize; index++) {
        maximum = std::max(maximum, (double)logits[index]);
    }
    if(!std::isfinite(maximum)) {
        return -std::numeric_limits<double>::infinity();
    }

    double exponentialSum = 0.0;
    for(int index = 0; index < vocabularySize; index++) {
        exponentialSum += std::exp((double)logits[index] - maximum);
    }
    if(!(exponentialSum > 0.0) || !std::isfinite(exponentialSum)) {
        return -std::numeric_limits<double>::infinity();
    }

    return (double)logits[selectedToken] - maximum - std::log(exponentialSum);
}
