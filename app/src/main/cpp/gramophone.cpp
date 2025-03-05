#include <jni.h>
#include "android_linker_ns.h"
#include "audio-legacy.h"
#include <dlfcn.h>
#include <android/log.h>
#include <cstdlib>
#include <string>
#include <vector>
#include <unistd.h>
#include <thread>

extern "C" {
#include <dlfunc.h>
}

#define LOG_TAG "AudioTrackHalInfo(JNI)"

static bool init_done = false;
static void* handle = nullptr;
typedef int audio_io_handle_t;
typedef audio_io_handle_t(*ZNK7android10AudioTrack9getOutputEv_t)(void*);
static ZNK7android10AudioTrack9getOutputEv_t ZNK7android10AudioTrack9getOutputEv = nullptr;
typedef uint32_t(*ZNK7android10AudioTrack16getHalSampleRateEv_t)(void*);
static ZNK7android10AudioTrack16getHalSampleRateEv_t ZNK7android10AudioTrack16getHalSampleRateEv = nullptr;
typedef uint32_t(*ZNK7android10AudioTrack18getHalChannelCountEv_t)(void*);
static ZNK7android10AudioTrack18getHalChannelCountEv_t ZNK7android10AudioTrack18getHalChannelCountEv = nullptr;
typedef uint32_t(*ZNK7android10AudioTrack12getHalFormatEv_t)(void*);
static ZNK7android10AudioTrack12getHalFormatEv_t ZNK7android10AudioTrack12getHalFormatEv = nullptr;
typedef int32_t status_t;
typedef status_t(*ZN7android11AudioSystem12getAudioPortEP13audio_port_v7_t)(void* port);
static ZN7android11AudioSystem12getAudioPortEP13audio_port_v7_t ZN7android11AudioSystem12getAudioPortEP13audio_port_v7 = nullptr;
typedef uint32_t(*ZNK7android10AudioTrack4dumpEiRKNS_6VectorINS_8String16EEE_t)(void*, int, void*);
static ZNK7android10AudioTrack4dumpEiRKNS_6VectorINS_8String16EEE_t ZNK7android10AudioTrack4dumpEiRKNS_6VectorINS_8String16EEE = nullptr;
typedef status_t(*ZN7android11AudioSystem14listAudioPortsE17audio_port_role_t17audio_port_type_tPjP13audio_port_v7S3_t)(LEGACY_audio_port_role_t, LEGACY_audio_port_type_t, unsigned int*, void*, unsigned int*);
static ZN7android11AudioSystem14listAudioPortsE17audio_port_role_t17audio_port_type_tPjP13audio_port_v7S3_t ZN7android11AudioSystem14listAudioPortsE17audio_port_role_t17audio_port_type_tPjP13audio_port_v7S3_ = nullptr;
static int gSampleRateOffset = 0;

bool initLib(JNIEnv* env) {
	if (init_done)
		return true;
	if (android_get_device_api_level() < 24) {
		if (!handle) {
			handle = dlopen("libmedia.so", RTLD_GLOBAL);
			if (handle == nullptr) {
				__android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
				                    "dlopen returned nullptr for libmedia.so: %s", dlerror());
				return false;
			}
		}
		init_done = true;
		return true;
	}
	linkernsbypass_load(env);
	if (android_get_device_api_level() < 26) {
		if (!handle) {
			handle = dlfunc_dlopen(env, "libmedia.so", RTLD_GLOBAL);
			if (handle == nullptr) {
				__android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
				                    "dlopen returned nullptr for libmedia.so: %s", dlerror());
				return false;
			}
		}
		init_done = true;
		return true;
	}
	if (!linkernsbypass_load_status()) {
		__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "linker namespace bypass init failed");
		return false;
	}
	android_namespace_t* ns = android_create_namespace_escape("default_copy", nullptr, nullptr,
	                                                          ANDROID_NAMESPACE_TYPE_SHARED, nullptr, nullptr);
	if (!handle) {
		handle = linkernsbypass_namespace_dlopen("libaudioclient.so", RTLD_GLOBAL, ns);
		if (handle == nullptr) {
			__android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
			                    "dlopen returned nullptr for libaudioclient.so: %s", dlerror());
			return false;
		}
	}
	init_done = true;
	return true;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_akanework_gramophone_logic_utils_AfFormatTracker_00024Companion_getHalSampleRateInternal(
		JNIEnv* env, jobject, jlong audioTrack) {
	if (!initLib(env))
		return 0;
	if (!ZNK7android10AudioTrack16getHalSampleRateEv) {
		ZNK7android10AudioTrack16getHalSampleRateEv =
				(ZNK7android10AudioTrack16getHalSampleRateEv_t)
						dlsym(handle, "_ZNK7android10AudioTrack16getHalSampleRateEv");
		if (ZNK7android10AudioTrack16getHalSampleRateEv == nullptr) {
			__android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
			                    "dlsym returned nullptr for _ZNK7android10AudioTrack16getHalSampleRateEv: %s",
			                    dlerror());
			return 0;
		}
	}
	return (int32_t) ZNK7android10AudioTrack16getHalSampleRateEv((void*) audioTrack);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_akanework_gramophone_logic_utils_AfFormatTracker_00024Companion_getHalChannelCountInternal(
		JNIEnv* env, jobject, jlong audioTrack) {
	if (!initLib(env))
		return 0;
	if (!ZNK7android10AudioTrack18getHalChannelCountEv) {
		ZNK7android10AudioTrack18getHalChannelCountEv =
				(ZNK7android10AudioTrack18getHalChannelCountEv_t)
						dlsym(handle, "_ZNK7android10AudioTrack18getHalChannelCountEv");
		if (ZNK7android10AudioTrack18getHalChannelCountEv == nullptr) {
			__android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
			                    "dlsym returned nullptr for _ZNK7android10AudioTrack18getHalChannelCountEv: %s",
			                    dlerror());
			return 0;
		}
	}
	return (int32_t) ZNK7android10AudioTrack18getHalChannelCountEv((void*) audioTrack);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_akanework_gramophone_logic_utils_AfFormatTracker_00024Companion_getHalFormatInternal(
		JNIEnv* env, jobject, jlong audioTrack) {
	if (!initLib(env))
		return 0;
	if (!ZNK7android10AudioTrack12getHalFormatEv) {
		ZNK7android10AudioTrack12getHalFormatEv =
				(ZNK7android10AudioTrack12getHalFormatEv_t)
						dlsym(handle, "_ZNK7android10AudioTrack12getHalFormatEv");
		if (ZNK7android10AudioTrack12getHalFormatEv == nullptr) {
			__android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
			                    "dlsym returned nullptr for _ZNK7android10AudioTrack12getHalFormatEv: %s",
			                    dlerror());
			return 0;
		}
	}
	return (int32_t) ZNK7android10AudioTrack12getHalFormatEv((void*) audioTrack);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_akanework_gramophone_logic_utils_AfFormatTracker_00024Companion_getOutputInternal(
		JNIEnv* env, jobject, jlong audioTrack) {
	if (!initLib(env))
		return 0;
	if (!ZNK7android10AudioTrack9getOutputEv) {
		ZNK7android10AudioTrack9getOutputEv =
				(ZNK7android10AudioTrack9getOutputEv_t)
						dlsym(handle, "_ZNK7android10AudioTrack9getOutputEv");
		if (ZNK7android10AudioTrack9getOutputEv == nullptr) {
			__android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
			                    "dlsym returned nullptr for _ZNK7android10AudioTrack9getOutputEv: %s",
			                    dlerror());
			return 0;
		}
	}
	return ZNK7android10AudioTrack9getOutputEv((void*) audioTrack);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_akanework_gramophone_logic_utils_AfFormatTracker_00024Companion_dumpInternal(
		JNIEnv* env, jobject, jlong audioTrack) {
	if (!initLib(env))
		return nullptr;
	if (!ZNK7android10AudioTrack4dumpEiRKNS_6VectorINS_8String16EEE) {
		ZNK7android10AudioTrack4dumpEiRKNS_6VectorINS_8String16EEE =
				(ZNK7android10AudioTrack4dumpEiRKNS_6VectorINS_8String16EEE_t)
						dlsym(handle, "_ZNK7android10AudioTrack4dumpEiRKNS_6VectorINS_8String16EEE");
		if (ZNK7android10AudioTrack4dumpEiRKNS_6VectorINS_8String16EEE == nullptr) {
			__android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
			                    "dlsym returned nullptr for _ZNK7android10AudioTrack4dumpEiRKNS_6VectorINS_8String16EEE: %s",
			                    dlerror());
			return nullptr;
		}
	}
	int pipe_fds[2];
	if (pipe(pipe_fds) == -1) {
		__android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
		                    "pipe() syscall failed");
		return nullptr;
	}

	std::string result;
	std::thread reader_thread([&] {
		char buffer[128];
		ssize_t bytes_read;
		while ((bytes_read = read(pipe_fds[0], buffer, sizeof(buffer) - 1)) > 0) {
			buffer[bytes_read] = '\0';
			result += buffer;
		}
		close(pipe_fds[0]);
	});

	// last argument is not allowed to be null, but where will we get a vector?
	ZNK7android10AudioTrack4dumpEiRKNS_6VectorINS_8String16EEE((void*)audioTrack, pipe_fds[1], nullptr);
	close(pipe_fds[1]);

	reader_thread.join();
	return env->NewStringUTF(result.c_str());
}

struct audio_gain_config  {
	[[maybe_unused]] int                  index;             /* index of the corresponding audio_gain in the
                                                                audio_port gains[] table */
	[[maybe_unused]] /*audio_gain_mode_t*/uint32_t    mode;              /* mode requested for this command */
	[[maybe_unused]] /*audio_channel_mask_t*/uint32_t channel_mask;      /* channels which gain value follows.
                                                                            N/A in joint mode */

	// note this "8" is not FCC_8, so it won't need to be changed for > 8 channels
	[[maybe_unused]] int                  values[sizeof(/*audio_channel_mask_t*/uint32_t) * 8]; /* gain values in millibels
													                                               for each channel ordered from LSb to MSb in
													                                               channel mask. The number of values is 1 in joint
													                                               mode or __builtin_popcount(channel_mask) */
	[[maybe_unused]] unsigned int         ramp_duration_ms; /* ramp duration in ms */
};

extern "C"
JNIEXPORT jint JNICALL
Java_org_akanework_gramophone_logic_utils_AfFormatTracker_00024Companion_findAfFlagsForPortInternal(
		JNIEnv* env, jobject, jint id, jint sampleRate, jboolean isForChannels) {
	if (!initLib(env))
		return INT32_MIN;
	if (!isForChannels && android_get_device_api_level() < 30) {
		// R added flags field to struct, but it is only populated since T. But app side may
		// want to bet on OEM modification that populates it in R/S.
		__android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
		                    "wrong usage of findAfFlagsForPortInternal: on this sdk, finding flags is impossible...");
		return INT32_MIN;
	}
	if (android_get_device_api_level() >= 28) {
		if (!ZN7android11AudioSystem12getAudioPortEP13audio_port_v7) {
			ZN7android11AudioSystem12getAudioPortEP13audio_port_v7 =
					(ZN7android11AudioSystem12getAudioPortEP13audio_port_v7_t)
							dlsym(handle,
							      "_ZN7android11AudioSystem12getAudioPortEP13audio_port_v7");
			if (ZN7android11AudioSystem12getAudioPortEP13audio_port_v7 == nullptr) {
				ZN7android11AudioSystem12getAudioPortEP13audio_port_v7 =
						(ZN7android11AudioSystem12getAudioPortEP13audio_port_v7_t)
								dlsym(handle,
								      "_ZN7android11AudioSystem12getAudioPortEP10audio_port");
				if (ZN7android11AudioSystem12getAudioPortEP13audio_port_v7 == nullptr) {
					__android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
					                    "dlsym returned nullptr for _ZN7android11AudioSystem12getAudioPortEP13audio_port_v7: %s",
					                    dlerror());
					return INT32_MIN;
				}
			}
		}
        if (gSampleRateOffset == 0 && sampleRate == 0) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                                "wrong usage of findAfFlagsForPortInternal: not calibrated and sampleRate is 0");
            return INT32_MAX;
        }
#define BUFFER_SIZE 114000
		auto buffer = (uint8_t *) calloc(1, BUFFER_SIZE); // should be plenty
		*((int * /*audio_port_handle_t*/) buffer) = id;
		ZN7android11AudioSystem12getAudioPortEP13audio_port_v7(buffer);
		uint8_t *pos = buffer + BUFFER_SIZE;
        if (gSampleRateOffset == 0) {
            while (buffer < pos) {
                pos -= sizeof(unsigned int) / sizeof(uint8_t);
                if (buffer < pos && *((unsigned int *) pos) == sampleRate)
                    break;
            }
            if (buffer < pos)
                gSampleRateOffset = pos - buffer;
        } else {
            pos += gSampleRateOffset;
        }
		if (buffer >= pos) {
			__android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
			                    "buffer(%p) >= pos(%p) (BUFFER_SIZE(%d))", buffer, pos,
			                    BUFFER_SIZE);
            gSampleRateOffset = 0;
			return INT32_MIN;
		}
		/*
		 * unsigned int             sample_rate;    <--- we are here
		 * audio_channel_mask_t     channel_mask;
		 * audio_format_t           format;
		 * struct audio_gain_config gain;
		 * union audio_io_flags     flags;          <--- we want to go here
		 */
		pos += sizeof(unsigned int) / sizeof(uint8_t); // unsigned int (sample_rate)
		if (!isForChannels) {
			pos += sizeof(uint32_t) / sizeof(uint8_t); // audio_channel_mask_t (channel_mask)
			pos += sizeof(uint32_t) / sizeof(uint8_t); // audio_format_t (format)
			pos += sizeof(struct audio_gain_config) / sizeof(uint8_t); // audio_gain_config (gain)
		}
		if (pos >= buffer + BUFFER_SIZE) {
			__android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
			                    "pos(%p) >= buffer(%p) + BUFFER_SIZE(%d)", pos, buffer,
			                    BUFFER_SIZE);
			return INT32_MIN;
		}
#undef BUFFER_SIZE
		return (int32_t) (*((uint32_t * /*audio_io_flags / audio_channel_mask_t*/) pos));
	} else {
		if (!ZN7android11AudioSystem14listAudioPortsE17audio_port_role_t17audio_port_type_tPjP13audio_port_v7S3_) {
			ZN7android11AudioSystem14listAudioPortsE17audio_port_role_t17audio_port_type_tPjP13audio_port_v7S3_ =
					(ZN7android11AudioSystem14listAudioPortsE17audio_port_role_t17audio_port_type_tPjP13audio_port_v7S3_t)
							dlsym(handle,
							      "_ZN7android11AudioSystem14listAudioPortsE17audio_port_role_t17audio_port_type_tPjP13audio_port_v7S3_");
			if (ZN7android11AudioSystem14listAudioPortsE17audio_port_role_t17audio_port_type_tPjP13audio_port_v7S3_ == nullptr) {
				ZN7android11AudioSystem14listAudioPortsE17audio_port_role_t17audio_port_type_tPjP13audio_port_v7S3_ =
						(ZN7android11AudioSystem14listAudioPortsE17audio_port_role_t17audio_port_type_tPjP13audio_port_v7S3_t)
								dlsym(handle,
								      "_ZN7android11AudioSystem14listAudioPortsE17audio_port_role_t17audio_port_type_tPjP10audio_portS3_");
				if (ZN7android11AudioSystem14listAudioPortsE17audio_port_role_t17audio_port_type_tPjP13audio_port_v7S3_ == nullptr) {
					__android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
					                    "dlsym returned nullptr for _ZN7android11AudioSystem14listAudioPortsE17audio_port_role_t17audio_port_type_tPjP10audio_portS3_: %s",
					                    dlerror());
					return INT32_MIN;
				}
			}
		}
		const bool oreo = android_get_device_api_level() >= 26;
		status_t status;
		unsigned int generation1 = 0;
		unsigned int generation;
		unsigned int numPorts;
		std::vector<audio_port_oreo> nPorts;
		std::vector<audio_port_legacy> nPortsOld;
		int attempts = 5;

		// get the port count and all the ports until they both return the same generation
		do {
			if (attempts-- < 0) {
				__android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
				                    "AudioSystem::listAudioPorts no attempts left");
				return INT32_MIN;
			}

			numPorts = 0;
			status = ZN7android11AudioSystem14listAudioPortsE17audio_port_role_t17audio_port_type_tPjP13audio_port_v7S3_(
					LEGACY_AUDIO_PORT_ROLE_SOURCE, LEGACY_AUDIO_PORT_TYPE_MIX, &numPorts,
					nullptr, &generation1);
			if (status != 0) {
				__android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
				                    "AudioSystem::listAudioPorts error %d", status);
				return INT32_MIN;
			}
			if (numPorts == 0) {
				__android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
				                    "AudioSystem::listAudioPorts found no ports");
				return INT32_MIN;
			}
			// Tuck on double the space to prevent heap corruption if OEM made the audio_port bigger
			if (oreo)
				nPorts.resize(numPorts * 2);
			else
				nPortsOld.resize(numPorts * 2);

			status = ZN7android11AudioSystem14listAudioPortsE17audio_port_role_t17audio_port_type_tPjP13audio_port_v7S3_(
					LEGACY_AUDIO_PORT_ROLE_SOURCE, LEGACY_AUDIO_PORT_TYPE_MIX, &numPorts,
					oreo ? (void*)&nPorts[0] : (void*)&nPortsOld[0], &generation);
		} while (generation1 != generation && status == 0);

		int i = 0;
		if (oreo) {
			for (auto port : nPorts) {
				if (i++ == numPorts) break; // needed because vector size > numPorts
				__android_log_print(ANDROID_LOG_INFO, LOG_TAG,
				                    "found port %d named %s", port.id, port.name);
				if (port.id == id) {
					return (int32_t) port.active_config.channel_mask;
				}
			}
		} else {
			for (auto port : nPortsOld) {
				if (i++ == numPorts) break; // needed because vector size > numPorts
				__android_log_print(ANDROID_LOG_INFO, LOG_TAG,
				                    "found port %d named %s", port.id, port.name);
				if (port.id == id) {
					return (int32_t) port.active_config.channel_mask;
				}
			}
		}
		return INT32_MAX;
	}
}