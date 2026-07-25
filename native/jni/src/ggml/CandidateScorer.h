#ifndef LATINIME_CANDIDATESCORER_H
#define LATINIME_CANDIDATESCORER_H

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "LanguageModel.h"

enum class CandidateScorerBosPolicy {
    ModelDefault = 0,
    Always = 1,
    Never = 2,
};

enum class CandidateScorerContextTruncation {
    KeepLast = 0,
    Reject = 1,
};

struct CandidateScorerConfig {
    int maxContextTokens = 256;
    int maxBatchSize = 16;
    bool supportsRightContext = true;
    CandidateScorerBosPolicy bosPolicy = CandidateScorerBosPolicy::ModelDefault;
    bool addEos = false;
    CandidateScorerContextTruncation contextTruncation =
            CandidateScorerContextTruncation::KeepLast;
};

struct CandidateScorerInput {
    std::string replacementText;
};

struct CandidateSequenceScore {
    double candidateLogProbability = 0.0;
    int candidateTokenCount = 0;
    double rightContextLogProbability = 0.0;
    int rightContextTokenCount = 0;
};

struct CandidateScorerDiagnostics {
    int64_t elapsedMicros = 0;
    int evaluatedCandidateTokens = 0;
    int evaluatedRightContextTokens = 0;
    int reusedPrefixTokens = 0;
    int batchCount = 0;
};

struct CandidateScorerResult {
    bool success = false;
    std::string error;
    std::vector<CandidateSequenceScore> scores;
    CandidateScorerDiagnostics diagnostics;
};

class CandidateScorer {
public:
    static std::unique_ptr<CandidateScorer> create(
            const std::string &modelPath,
            const CandidateScorerConfig &config,
            std::string *error);

    CandidateScorerResult score(
            const std::string &leftContext,
            const std::string &rightContext,
            const std::vector<CandidateScorerInput> &candidates,
            int rightContextTokenLimit);

    const CandidateScorerConfig &config() const {
        return mConfig;
    }

    LanguageModel *model() const {
        return mModel.get();
    }

private:
    CandidateScorer(std::unique_ptr<LanguageModel> model, CandidateScorerConfig config);

    bool decodePrefix(
            const token_sequence &tokens,
            std::vector<float> *outLogits,
            int *batchCount,
            std::string *error);

    bool decodeAndScoreSequence(
            llama_seq_id sequenceId,
            int startPosition,
            const token_sequence &candidateTokens,
            const token_sequence &rightContextTokens,
            const std::vector<float> &prefixLogits,
            CandidateSequenceScore *score,
            int *batchCount,
            std::string *error);

    static double selectedTokenLogProbability(
            const float *logits,
            int vocabularySize,
            llama_token selectedToken);

    std::unique_ptr<LanguageModel> mModel;
    CandidateScorerConfig mConfig;
};

#endif // LATINIME_CANDIDATESCORER_H
