import urllib.request
import json
import sys
import time

API_KEY = "e5cfab60-957e-4407-838c-62de0a1480d9"

DEVICES = {
    "fence":   ("1C:AB:D3:32:34:39:45:05", "H7039"),
    "pergola": ("1A:21:D2:2D:44:46:06:3C", "H7039"),
    "playset": ("D8:86:D9:38:30:33:72:53", "H705A"),
    "icicle":  ("0C:47:C5:09:26:1A:16:58", "H70D1"),
}

# Devices that get powered on before a scene and powered off for "off"
POWER_MANAGED = {"icicle"}

SCENES = {
    "xmas":       {"fence": 3970155, "pergola": 3970262, "playset": 3147470, "icicle": 4503332},
    "stpats":     {"fence": 3970161, "pergola": 3970270, "playset": 3970314, "icicle": "off"},
    "valentines": {"fence": 3970163, "pergola": 3970267, "playset": 3970309, "icicle": 4503342},
    "easter":     {"fence": 3970173, "pergola": 3970275, "playset": 2489584, "icicle": "off"},
    "halloween":  {"fence": 4282186, "pergola": 4282181, "playset": 3970308, "icicle": 4503343},
    "thanks":     {"fence": 3970179, "pergola": 3970269, "playset": 3970311, "icicle": 4503347},
    "rwb":        {"fence": 2614156, "pergola": 3970261, "playset": 3970298, "icicle": 4503345},
    "default":    {"fence": 3970319, "pergola": 3970321, "playset": 3970317, "icicle": "off"},
    "seahawks":   {"fence": 3970156, "pergola": 3970263, "playset": 1455380, "icicle": 4503334},
    "huskers":    {"fence": 3970158, "pergola": 3970264, "playset": 1455422, "icicle": 4503339},
    "cyclones":   {"fence": 3970159, "pergola": 3970265, "playset": 3970306, "icicle": 4503338},
    "royals":     {"fence": 3970160, "pergola": 3970266, "playset": 3970307, "icicle": 4503350},
}

def send_control(device_id, sku, capability):
    payload = {
        "requestId": "1",
        "payload": {
            "sku": sku,
            "device": device_id,
            "capability": capability
        }
    }
    data = json.dumps(payload).encode()
    req = urllib.request.Request(
        "https://openapi.api.govee.com/router/api/v1/device/control",
        data=data,
        headers={"Govee-API-Key": API_KEY, "Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            result = json.loads(resp.read().decode())
            return result.get("code") == 200
    except Exception as e:
        print(f"  error: {e}")
        return False

def trigger_snapshot(device_id, sku, value):
    return send_control(device_id, sku, {
        "type": "devices.capabilities.dynamic_scene",
        "instance": "snapshot",
        "value": value
    })

def trigger_power(device_id, sku, on):
    return send_control(device_id, sku, {
        "type": "devices.capabilities.on_off",
        "instance": "powerSwitch",
        "value": 1 if on else 0
    })

def apply_holiday(holiday):
    if holiday not in SCENES:
        print(f"Unknown holiday: {holiday}")
        return
    for device_name, value in SCENES[holiday].items():
        if value is None:
            print(f"Skipping {device_name} - no snapshot defined")
            continue
        device_id, sku = DEVICES[device_name]
        if value == "off":
            ok = trigger_power(device_id, sku, False)
        else:
            if device_name in POWER_MANAGED:
                trigger_power(device_id, sku, True)
                time.sleep(1)
            ok = trigger_snapshot(device_id, sku, value)
        print(f"{'OK' if ok else 'FAIL'} {device_name} -> {holiday}")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python3 govee_holiday.py <holiday>")
    else:
        apply_holiday(sys.argv[1])
