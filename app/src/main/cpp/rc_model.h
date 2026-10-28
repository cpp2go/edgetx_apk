// Which DJI remote this build is running on.
//
// The same list as RcModel.java, read from the same property, so the native side and the Java
// side cannot disagree about which remote they are driving - the two have to: the switch types
// written into radio.yml, the return button's gesture and the layout the SDK buttons are mapped
// with all depend on it.
#pragma once

#include <sys/system_properties.h>

#include <string>

enum class RemoteModel {
    RcPlus2,   // rc701 - the remote this port started on
    RcPro,     // rm510, rm510b, rm510bv - RC Pro / RC Pro Enterprise
    RcPlus,    // rm700, rm700_enterprise - the RC Plus (M30 / M300)
    RcPro2,    // rc520 - RC Pro 2
    Unknown,   // anything else: the RC Plus 2's behaviour, as before

    // Not a model: a table entry that applies to every remote. Only ever written in a table,
    // never returned by remote_model().
    All,
};

// ro.product.device, i.e. Build.DEVICE. Cached: it cannot change while the process lives.
inline const std::string& remote_device() {
    static const std::string device = [] {
        char value[PROP_VALUE_MAX] = {0};
        __system_property_get("ro.product.device", value);
        return std::string(value);
    }();
    return device;
}

inline RemoteModel remote_model() {
    static const RemoteModel model = [] {
        const std::string& device = remote_device();
        if (device == "rc701") return RemoteModel::RcPlus2;
        // The suffixes are model revisions of the same remote (b, bv = Enterprise).
        if (device.compare(0, 5, "rm510") == 0) return RemoteModel::RcPro;
        if (device.compare(0, 5, "rm700") == 0) return RemoteModel::RcPlus;
        if (device == "rc520") return RemoteModel::RcPro2;
        return RemoteModel::Unknown;
    }();
    return model;
}

inline const char* remote_model_name(RemoteModel model) {
    switch (model) {
        case RemoteModel::RcPlus2: return "RC Plus 2";
        case RemoteModel::RcPro: return "RC Pro";
        case RemoteModel::RcPlus: return "RC Plus";
        case RemoteModel::RcPro2: return "RC Pro 2";
        case RemoteModel::All: return "every remote";
        default: return "unrecognised remote";
    }
}
