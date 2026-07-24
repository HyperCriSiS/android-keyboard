#ifndef LATINIME_GGUF_CANDIDATE_RANKER_NATIVE_BRIDGE_H
#define LATINIME_GGUF_CANDIDATE_RANKER_NATIVE_BRIDGE_H

#include "jni.h"

namespace latinime {
    int register_GgufCandidateRankerNativeBridge(JNIEnv *env);
}

#endif // LATINIME_GGUF_CANDIDATE_RANKER_NATIVE_BRIDGE_H
