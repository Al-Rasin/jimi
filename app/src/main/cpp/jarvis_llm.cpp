// JNI bridge for llm/Llama.kt (PLAN 9.1). One handle = model + context + the tokens now in the KV cache.
// Each call keeps the longest common token prefix in the KV cache (the fixed system prompt / tool list
// is read once, BENCHMARKS: 600 tokens ≈ 2.7 s uncached), and only decodes the new tail.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "llama.h"

#define TAG "JarvisLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Handle {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::vector<llama_token> cached;   // tokens whose K/V are in the cache, in order
};

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, false, true);
    std::vector<llama_token> out(n);
    if (llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), out.data(), n, false, true) < 0) out.clear();
    return out;
}

std::string piece(const llama_vocab *vocab, llama_token t) {
    char buf[256];
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, true);
    return n > 0 ? std::string(buf, n) : std::string();
}

// Length of the longest prefix of s that ends on a full UTF-8 character.
size_t utf8_complete(const std::string &s) {
    size_t i = s.size();
    size_t back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = s[i - 1];
        if ((c & 0xC0) != 0x80) {   // lead byte
            size_t need = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
            return back + 1 >= need ? s.size() : i - 1;
        }
        i--;
        back++;
    }
    return s.size();
}

jstring to_jstring(JNIEnv *env, const std::string &s) {
    jbyteArray bytes = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) s.size(), (const jbyte *) s.data());
    jclass str = env->FindClass("java/lang/String");
    jmethodID init = env->GetMethodID(str, "<init>", "([BLjava/lang/String;)V");
    jstring cs = env->NewStringUTF("UTF-8");
    auto out = (jstring) env->NewObject(str, init, bytes, cs);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(cs);
    env->DeleteLocalRef(str);
    return out;
}

bool decode(Handle *h, const llama_token *tokens, int n) {
    int batch = (int) llama_n_batch(h->ctx);
    for (int i = 0; i < n; i += batch) {
        int m = std::min(batch, n - i);
        if (llama_decode(h->ctx, llama_batch_get_one(const_cast<llama_token *>(tokens + i), m)) != 0) return false;
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_alrasin_jarvis_llm_Llama_nativeLoad(JNIEnv *env, jclass, jstring jpath, jint n_ctx, jint threads) {
    static bool init = false;
    if (!init) { llama_backend_init(); init = true; }
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    auto mp = llama_model_default_params();
    mp.use_mmap = true;
    mp.use_mlock = false;
    llama_model *model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) { LOGE("model load failed"); return 0; }
    auto cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { LOGE("context init failed"); llama_model_free(model); return 0; }
    auto *h = new Handle();
    h->model = model;
    h->ctx = ctx;
    h->vocab = llama_model_get_vocab(model);
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_alrasin_jarvis_llm_Llama_nativeGenerate(JNIEnv *env, jclass, jlong handle, jstring jprompt, jstring jgrammar,
                                                 jint max_tokens, jfloat temperature, jobject cb) {
    auto *h = reinterpret_cast<Handle *>(handle);
    const char *p = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(p);
    env->ReleaseStringUTFChars(jprompt, p);
    std::vector<llama_token> tokens = tokenize(h->vocab, prompt);
    if (tokens.empty()) return;
    int n_ctx = (int) llama_n_ctx(h->ctx);
    if ((int) tokens.size() + max_tokens > n_ctx) {
        LOGE("prompt too long: %zu tokens", tokens.size());
        tokens.erase(tokens.begin(), tokens.begin() + ((int) tokens.size() + max_tokens - n_ctx));
        h->cached.clear();
    }

    // keep the common prefix, drop the rest; always decode at least one token to get fresh logits
    size_t keep = 0;
    while (keep < h->cached.size() && keep < tokens.size() && h->cached[keep] == tokens[keep]) keep++;
    if (keep == tokens.size()) keep--;
    llama_memory_t mem = llama_get_memory(h->ctx);
    if (!llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1)) { llama_memory_seq_rm(mem, -1, -1, -1); keep = 0; }
    h->cached.resize(keep);
    if (!decode(h, tokens.data() + keep, (int) (tokens.size() - keep))) {
        LOGE("decode failed");
        llama_memory_seq_rm(mem, -1, -1, -1);
        h->cached.clear();
        return;
    }
    h->cached = tokens;
    LOGI("prompt %zu tokens, %zu cached", tokens.size(), keep);

    auto chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (jgrammar) {
        const char *g = env->GetStringUTFChars(jgrammar, nullptr);
        llama_sampler *gs = llama_sampler_init_grammar(h->vocab, g, "root");
        env->ReleaseStringUTFChars(jgrammar, g);
        if (gs) llama_sampler_chain_add(chain, gs); else LOGE("grammar parse failed, running without it");
    }
    if (temperature > 0) {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    }

    jclass cls = env->GetObjectClass(cb);
    jmethodID on_piece = env->GetMethodID(cls, "piece", "(Ljava/lang/String;)Z");
    std::string pending;
    for (int i = 0; i < max_tokens; i++) {
        llama_token t = llama_sampler_sample(chain, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, t)) break;
        pending += piece(h->vocab, t);
        size_t ready = utf8_complete(pending);
        bool go = true;
        if (ready > 0) {
            jstring js = to_jstring(env, pending.substr(0, ready));
            go = env->CallBooleanMethod(cb, on_piece, js);
            env->DeleteLocalRef(js);
            pending.erase(0, ready);
            if (env->ExceptionCheck()) { env->ExceptionClear(); go = false; }
        }
        if (!go || (int) h->cached.size() + 1 >= n_ctx) break;
        if (llama_decode(h->ctx, llama_batch_get_one(&t, 1)) != 0) break;
        h->cached.push_back(t);
    }
    llama_sampler_free(chain);
}

extern "C" JNIEXPORT void JNICALL
Java_com_alrasin_jarvis_llm_Llama_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (!h) return;
    llama_free(h->ctx);
    llama_model_free(h->model);
    delete h;
}
