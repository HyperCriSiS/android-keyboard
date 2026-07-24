//
// Created by alex on 7/24/23.
//

#include <cstring>
#include <sentencepiece/sentencepiece_processor.h>
#include "LanguageModel.h"
#include "ModelMeta.h"

LanguageModel::LanguageModel(LlamaAdapter *adapter): adapter(adapter) { }

LlamaAdapter::~LlamaAdapter() {
    if(batch.token != nullptr || batch.embd != nullptr) {
        llama_batch_free(batch);
    }
    if(context != nullptr) {
        llama_free(context);
        context = nullptr;
    }
    if(model != nullptr) {
        llama_free_model(model);
        model = nullptr;
    }
}

bool LlamaAdapter::usesExternalSentencePiece() const {
    return metadata.ext_tokenizer_type == ExternalTokenizerType::SentencePiece;
}

int LlamaAdapter::getVocabSize() const {
    if(usesExternalSentencePiece()) {
        // KeyboardLM models may intentionally use an external tokenizer whose vocabulary is a
        // strict subset of the model vocabulary.
        return spm.GetPieceSize();
    }

    return model == nullptr ? 0 : llama_n_vocab(model);
}

const char *LlamaAdapter::getToken(int id) const {
    if(id < 0 || id >= getVocabSize()) return "";

    if(usesExternalSentencePiece()) {
        tokenTextBuffer = spm.IdToPiece(id);
        return tokenTextBuffer.c_str();
    }

    const char *text = llama_token_get_text(model, id);
    return text == nullptr ? "" : text;
}

bool LlamaAdapter::eval(int nPast, token_sequence input, std::vector<float> &outLogits) {
    ASSERT(nPast >= 0);
    ASSERT(nPast + input.size() < LLAMA_CONTEXT_SIZE);

    if(input.empty()) return false;
    if(llama_eval(context, input.data(), input.size(), nPast) != 0) {
        return false;
    }

    // TODO: Zero-copy
    outLogits.resize(llama_n_vocab(model));
    memcpy(outLogits.data(), llama_get_logits(context), llama_n_vocab(model) * sizeof(float));

    return true;
}

std::vector<int> LlamaAdapter::tokenize(const char *text, bool addBos, bool special) const {
    const char *safeText = text == nullptr ? "" : text;

    if(usesExternalSentencePiece()) {
        std::vector<int> result = spm.EncodeAsIds(safeText);
        if(addBos) {
            const llama_token bos = bosToken();
            if(bos >= 0) result.insert(result.begin(), bos);
        }
        return result;
    }

    const int textLength = (int)strlen(safeText);
    int tokenCount = llama_tokenize(
            model,
            safeText,
            textLength,
            nullptr,
            0,
            addBos,
            special);
    if(tokenCount == 0) return {};

    const int capacity = tokenCount < 0 ? -tokenCount : tokenCount;
    std::vector<llama_token> result((size_t)capacity);
    tokenCount = llama_tokenize(
            model,
            safeText,
            textLength,
            result.data(),
            capacity,
            addBos,
            special);
    if(tokenCount < 0) {
        AKLOGE("Native GGUF tokenizer result exceeded allocated capacity");
        return {};
    }

    result.resize((size_t)tokenCount);
    return result;
}

int LlamaAdapter::tokenToId(const char *text) const {
    if(text == nullptr) return 0;

    if(usesExternalSentencePiece()) {
        return spm.PieceToId(text);
    }

    const std::vector<int> encoded = tokenize(text, false, true);
    if(encoded.size() == 1) return encoded.front();

    // Some tokenizers normalize input even when special token parsing is enabled. Fall back to an
    // exact vocabulary lookup for adapter configuration and legacy special-token discovery.
    const int vocabularySize = getVocabSize();
    for(int id = 0; id < vocabularySize; id++) {
        const char *piece = llama_token_get_text(model, id);
        if(piece != nullptr && strcmp(piece, text) == 0) return id;
    }

    // Legacy callers use 0 as the "not found" sentinel.
    return 0;
}

std::string LlamaAdapter::decode(const token_sequence &tokens) const {
    if(usesExternalSentencePiece()) {
        return spm.DecodeIds(tokens);
    }

    std::string result;
    std::vector<char> buffer(256);
    for(const llama_token token : tokens) {
        int pieceLength = llama_token_to_piece(model, token, buffer.data(), (int)buffer.size());
        if(pieceLength < 0) {
            buffer.resize((size_t)(-pieceLength));
            pieceLength = llama_token_to_piece(model, token, buffer.data(), (int)buffer.size());
        }
        if(pieceLength > 0) {
            result.append(buffer.data(), (size_t)pieceLength);
        }
    }
    return result;
}

llama_token LlamaAdapter::bosToken() const {
    return model == nullptr ? -1 : llama_token_bos(model);
}

llama_token LlamaAdapter::eosToken() const {
    return model == nullptr ? -1 : llama_token_eos(model);
}

bool LlamaAdapter::modelAddsBosByDefault() const {
    return model != nullptr && llama_add_bos_token(model) == 1;
}

bool LlamaAdapter::modelAddsEosByDefault() const {
    return model != nullptr && llama_add_eos_token(model) == 1;
}

LanguageModel *LlamaAdapter::createLanguageModel(const std::string &modelPath) {
    auto adapter = new LlamaAdapter();
    adapter->metadata = loadModelMetadata(modelPath);
    if(adapter->metadata.error) {
        AKLOGE("Could not read GGUF model metadata");
        delete adapter;
        return nullptr;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = LLAMA_CONTEXT_SIZE;
    ctx_params.n_threads = 1;
    ctx_params.n_threads_batch = 1;

    adapter->n_batch = ctx_params.n_batch;

    llama_model_params model_params = llama_model_default_params();

    adapter->model = llama_load_model_from_file(modelPath.c_str(), model_params);
    if(adapter->model == nullptr) {
        delete adapter;
        return nullptr;
    }

    adapter->context = llama_new_context_with_model(adapter->model, ctx_params);
    if(adapter->context == nullptr) {
        AKLOGE("Could not create llama context");
        delete adapter;
        return nullptr;
    }

    if(adapter->metadata.ext_tokenizer_type == ExternalTokenizerType::SentencePiece) {
        auto spm_load_result = adapter->spm.LoadFromSerializedProto(adapter->metadata.ext_tokenizer_data);
        if(!spm_load_result.ok()) {
            AKLOGE("SPM load failed: %s", spm_load_result.ToString().c_str());
            delete adapter;
            return nullptr;
        }
    } else if(adapter->metadata.ext_tokenizer_type == ExternalTokenizerType::None) {
        AKLOGI("Using tokenizer embedded in standard GGUF model");
    } else {
        AKLOGE("Unknown external tokenizer type");
        delete adapter;
        return nullptr;
    }

    adapter->batch = llama_batch_init(LLAMA_CONTEXT_SIZE, 0, 1);

    if(adapter->metadata.HasFeature(FEATURE_EMBED_MIXING)) {
        adapter->embeddings.resize(llama_n_embd(adapter->model) * llama_n_vocab(adapter->model));

        auto tensor = llama_get_model_tensor(adapter->model, "token_embd.weight");
        ASSERT(tensor);

        if (tensor->type != GGML_TYPE_F32) {
            ggml_internal_get_type_traits(tensor->type).to_float(tensor->data,
                                                                 adapter->embeddings.data(),
                                                                 adapter->embeddings.size());
        } else {
            ASSERT((tensor->ne[0] * tensor->ne[1]) == adapter->embeddings.size());
            memcpy(adapter->embeddings.data(), tensor->data,
                   adapter->embeddings.size() * sizeof(float));
        }
    }

    if(adapter->metadata.HasFeature(FEATURE_ENCODER)) {
        adapter->encoder_weight.resize(llama_n_embd(adapter->model) * 2);
        adapter->encoder_bias.resize(llama_n_embd(adapter->model));

        for(int i = 0; i < llama_n_embd(adapter->model); i++) {
            adapter->encoder_weight[i*2]     = adapter->embeddings.data()[FEATURE_ENCODER_W_X_ID * llama_n_embd(adapter->model) + i];
            adapter->encoder_weight[i*2 + 1] = adapter->embeddings.data()[FEATURE_ENCODER_W_Y_ID * llama_n_embd(adapter->model) + i];
            adapter->encoder_bias[i]         = adapter->embeddings.data()[FEATURE_ENCODER_B_ID * llama_n_embd(adapter->model) + i];
        }
    }

    return new LanguageModel(adapter);
}

LlamaAdapter::LlamaAdapter() = default;
