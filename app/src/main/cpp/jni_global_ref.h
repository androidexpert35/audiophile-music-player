// ─────────────────────────────────────────────────────────────────────────────
// jni_global_ref.h
//
// RAII ownership for a JNI global reference held by native callbacks.
//
// Native callback objects (e.g. the DecoderToRingBridge EOF lambda) may hold a
// jobject global reference far beyond the JNI call that created it, and the
// callback is not guaranteed to ever fire (the user can skip the track before
// EOF).  Releasing the reference must therefore be tied to the *destruction*
// of the callback, not to its invocation — otherwise every skipped track leaks
// one global reference.
//
// Wrap the reference in a shared_ptr<JniGlobalRef> captured by the callback
// lambda: whenever the lambda is destroyed (bridge teardown, callback
// replacement, or after firing), the last shared_ptr release deletes the
// global reference on whatever thread that happens, attaching to the JVM only
// if the thread is not already attached.
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

#include <jni.h>

class JniGlobalRef {
public:
    /**
     * Takes ownership of `global_ref`, which must have been created with
     * `env->NewGlobalRef()`.  `vm` must outlive this object (the JavaVM lives
     * for the process lifetime on Android).
     */
    JniGlobalRef(JavaVM *vm, jobject global_ref) noexcept
        : vm_(vm), ref_(global_ref) {}

    ~JniGlobalRef()
    {
        if (vm_ == nullptr || ref_ == nullptr) return;

        // Prefer the current thread's existing attachment: detaching a thread
        // that was attached elsewhere (e.g. a Kotlin-managed thread) would
        // corrupt that attachment.
        JNIEnv *env = nullptr;
        if (vm_->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK
            && env != nullptr) {
            env->DeleteGlobalRef(ref_);
            return;
        }

        // Unattached native thread: attach as daemon just for the delete.
        if (vm_->AttachCurrentThreadAsDaemon(&env, nullptr) == JNI_OK && env != nullptr) {
            env->DeleteGlobalRef(ref_);
            vm_->DetachCurrentThread();
        }
        // If attach fails the reference leaks; the JVM is shutting down at
        // that point, so the leak is process-terminal and harmless.
    }

    JniGlobalRef(const JniGlobalRef &)            = delete;
    JniGlobalRef &operator=(const JniGlobalRef &) = delete;

    [[nodiscard]] jobject get() const noexcept { return ref_; }

private:
    JavaVM *vm_  = nullptr;
    jobject ref_ = nullptr;
};
