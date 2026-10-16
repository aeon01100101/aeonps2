LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := aeonps2_bridge
LOCAL_SRC_FILES := aeonps2_bridge.c
LOCAL_CFLAGS := -std=c11 -Wall
LOCAL_LDLIBS := -llog -landroid -ldl
include $(BUILD_SHARED_LIBRARY)
