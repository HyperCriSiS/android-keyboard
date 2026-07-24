#define LOG_TAG "LatinIME: jni: GgufCandidateRanker"

#include "org_futo_inputmethod_latin_languagepack_ranker_GgufCandidateRankerNativeBridge.h"

#include <memory>
#include <string>
#include <vector>

#include "jni_common.h"
#include "jni_utils.h"
#include "ggml/CandidateScorer.h"
#include "defines.h"

namespace latinime {
namespace {

void setError(JNIEnv *env, jobjectArray outError, const std::string &message) {
    if(outError == nullptr || env->GetArrayLength(outError) < 1) return;
    jstring value = string2jstring(env, message.c_str());
    env->SetObjectArrayElement(outError, 0, value);
    env->DeleteLocalRef(value);
}

CandidateScorerBosPolicy parseBosPolicy(jint value, bool *valid) {
    *valid = true;
    switch(value) {
        case 0:
            return CandidateScorerBosPolicy::ModelDefault;
        case 1:
            return CandidateScorerBosPolicy::Always;
        case 2:
            return CandidateScorerBosPolicy::Never;
        default:
            *valid = false;
            return CandidateScorerBosPolicy::ModelDefault;
    }
}

CandidateScorerContextTruncation parseTruncation(jint value, bool *valid) {
    *valid = true;
    switch(value) {
        case 0:
            return CandidateScorerContextTruncation::KeepLast;
        case 1:
            return CandidateScorerContextTruncation::Reject;
        default:
            *valid = false;
            return CandidateScorerContextTruncation::KeepLast;
    }
}

jlong openNative(
        JNIEnv *env,
        jobject,
        jstring modelPath,
        jint maxContextTokens,
        jint maxBatchSize,
        jboolean supportsRightContext,
        jint bosPolicy,
        jboolean addEos,
        jint contextTruncation,
        jobjectArray outError) {
    if(modelPath == nullptr) {
        setError(env, outError, "Model path is null.");
        return 0;
    }

    bool bosValid = false;
    bool truncationValid = false;
    CandidateScorerConfig config;
    config.maxContextTokens = maxContextTokens;
    config.maxBatchSize = maxBatchSize;
    config.supportsRightContext = supportsRightContext == JNI_TRUE;
    config.bosPolicy = parseBosPolicy(bosPolicy, &bosValid);
    config.addEos = addEos == JNI_TRUE;
    config.contextTruncation = parseTruncation(contextTruncation, &truncationValid);

    if(!bosValid || !truncationValid) {
        setError(env, outError, "Native ranker configuration contains an unknown enum value.");
        return 0;
    }

    std::string error;
    std::unique_ptr<CandidateScorer> scorer = CandidateScorer::create(
            jstring2string(env, modelPath),
            config,
            &error);
    if(scorer == nullptr) {
        setError(env, outError, error.empty() ? "Could not open candidate ranker." : error);
        return 0;
    }

    return reinterpret_cast<jlong>(scorer.release());
}

void closeNative(JNIEnv *, jobject, jlong state) {
    auto *scorer = reinterpret_cast<CandidateScorer *>(state);
    delete scorer;
}

jstring scoreNative(
        JNIEnv *env,
        jobject,
        jlong state,
        jstring leftContext,
        jstring rightContext,
        jobjectArray candidates,
        jint rightContextTokenLimit,
        jdoubleArray outCandidateLogProbabilities,
        jintArray outCandidateTokenCounts,
        jdoubleArray outRightContextLogProbabilities,
        jintArray outRightContextTokenCounts,
        jlongArray outDiagnostics) {
    auto *scorer = reinterpret_cast<CandidateScorer *>(state);
    if(scorer == nullptr) {
        return string2jstring(env, "Native candidate ranker is closed.");
    }
    if(leftContext == nullptr || rightContext == nullptr || candidates == nullptr) {
        return string2jstring(env, "Candidate ranker input contains null values.");
    }

    const jsize candidateCount = env->GetArrayLength(candidates);
    if(candidateCount <= 0 ||
            env->GetArrayLength(outCandidateLogProbabilities) != candidateCount ||
            env->GetArrayLength(outCandidateTokenCounts) != candidateCount ||
            env->GetArrayLength(outRightContextLogProbabilities) != candidateCount ||
            env->GetArrayLength(outRightContextTokenCounts) != candidateCount ||
            env->GetArrayLength(outDiagnostics) < 5) {
        return string2jstring(env, "Candidate ranker output array sizes are invalid.");
    }

    std::vector<CandidateScorerInput> nativeCandidates;
    nativeCandidates.reserve((size_t)candidateCount);
    for(jsize index = 0; index < candidateCount; index++) {
        auto value = (jstring)env->GetObjectArrayElement(candidates, index);
        if(value == nullptr) {
            return string2jstring(env, "Candidate replacement text is null.");
        }
        nativeCandidates.push_back({jstring2string(env, value)});
        env->DeleteLocalRef(value);
    }

    CandidateScorerResult result = scorer->score(
            jstring2string(env, leftContext),
            jstring2string(env, rightContext),
            nativeCandidates,
            rightContextTokenLimit);
    if(!result.success) {
        return string2jstring(
                env,
                result.error.empty() ? "Native candidate scoring failed." : result.error.c_str());
    }
    if(result.scores.size() != (size_t)candidateCount) {
        return string2jstring(env, "Native candidate scorer returned an invalid score count.");
    }

    std::vector<jdouble> candidateLogProbabilities((size_t)candidateCount);
    std::vector<jint> candidateTokenCounts((size_t)candidateCount);
    std::vector<jdouble> rightContextLogProbabilities((size_t)candidateCount);
    std::vector<jint> rightContextTokenCounts((size_t)candidateCount);
    for(jsize index = 0; index < candidateCount; index++) {
        const CandidateSequenceScore &score = result.scores[(size_t)index];
        candidateLogProbabilities[(size_t)index] = score.candidateLogProbability;
        candidateTokenCounts[(size_t)index] = score.candidateTokenCount;
        rightContextLogProbabilities[(size_t)index] = score.rightContextLogProbability;
        rightContextTokenCounts[(size_t)index] = score.rightContextTokenCount;
    }

    env->SetDoubleArrayRegion(
            outCandidateLogProbabilities,
            0,
            candidateCount,
            candidateLogProbabilities.data());
    env->SetIntArrayRegion(
            outCandidateTokenCounts,
            0,
            candidateCount,
            candidateTokenCounts.data());
    env->SetDoubleArrayRegion(
            outRightContextLogProbabilities,
            0,
            candidateCount,
            rightContextLogProbabilities.data());
    env->SetIntArrayRegion(
            outRightContextTokenCounts,
            0,
            candidateCount,
            rightContextTokenCounts.data());

    const jlong diagnostics[5] = {
            (jlong)result.diagnostics.elapsedMicros,
            (jlong)result.diagnostics.evaluatedCandidateTokens,
            (jlong)result.diagnostics.evaluatedRightContextTokens,
            (jlong)result.diagnostics.reusedPrefixTokens,
            (jlong)result.diagnostics.batchCount,
    };
    env->SetLongArrayRegion(outDiagnostics, 0, 5, diagnostics);
    return nullptr;
}

const JNINativeMethod methods[] = {
        {
                const_cast<char *>("openNative"),
                const_cast<char *>("(Ljava/lang/String;IIZIZI[Ljava/lang/String;)J"),
                reinterpret_cast<void *>(openNative),
        },
        {
                const_cast<char *>("closeNative"),
                const_cast<char *>("(J)V"),
                reinterpret_cast<void *>(closeNative),
        },
        {
                const_cast<char *>("scoreNative"),
                const_cast<char *>(
                        "(JLjava/lang/String;Ljava/lang/String;[Ljava/lang/String;I[D[I[D[I[J)Ljava/lang/String;"),
                reinterpret_cast<void *>(scoreNative),
        },
};

} // namespace

int register_GgufCandidateRankerNativeBridge(JNIEnv *env) {
    const char *className =
            "org/futo/inputmethod/latin/languagepack/ranker/GgufCandidateRankerNativeBridge";
    return registerNativeMethods(env, className, methods, NELEMS(methods));
}

} // namespace latinime
