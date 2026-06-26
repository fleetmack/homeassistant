import urllib.request
import json
import sys

API_KEY = "e5cfab60-957e-4407-838c-62de0a1480d9"

DEVICES = {
    "fence":   ("1C:AB:D3:32:34:39:45:05", "H7039"),
    "pergola": ("1A:21:D2:2D:44:46:06:3C", "H7039"),
    "playset": ("D8:86:D9:38:30:33:72:53", "H705A"),
}

SCENES = {
    "xmas":       {"fence": 3970155, "pergola": 3970262, "playset": 3147470},
    "stpats":     {"fence": 3970161, "pergola": 3970270, "playset": 3970314},
    "valentines": {"fence": 3970163, "pergola": 3970267, "playset": 3970309},
    "easter":     {"fence": 3970173, "pergola": 3970275, "playset": 2489584},
    "halloween":  {"fence": 3970176, "pergola": 3970268, "playset": 3970308},
    "thanks":     {"fence": 3970179, "pergola": 3970269, "playset": 3970311},
    "rwb":        {"fence": 2614156, "pergola": 3970261, "playset": 3970298},
    "default":    {"fence": 3970319, "pergola": 3970321, "playset": 3970317},
    "seahawks":   {"fence": 3970156, "pergola": 3970263, "playset": 1455380},
    "huskers":    {"fence": 3970158, "pergola": 3970264, "playset": 1455422},
    "cyclones":   {"fence": 3970159, "pergola": 3970265, "playset": 3970306}, 
     "royals":     {"fence": 3970160, "pergola": 3970266, "playset": 3970307},
}

def trigger_snapshot(device_id, sku, value):
    payload = {
        "requestId": "1",
        "payload": {
            "sku": sku,
            "device": device_id,
            "capability": {
                "type": "devices.capabilities.dynamic_scene",
                "instance": "snapshot",
                "value": value
            }
        }
    }
    data = json.dumps(payload).encode()
    req = urllib.request.Request(
        "https://openapi.api.govee.com/router/api/v1/device/control",
        data=data,
        headers={"Govee-API-Key": API_KEY, "Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req) as resp:
        result = json.loads(resp.read().decode())
        return result.get("code") == 200

def apply_holiday(holiday):
    if holiday not in SCENES:
        print(f"Unknown holiday: {holiday}")
        sys.exit(1)
    scene = SCENES[holiday]
    for device_name, value in scene.items():
        if value is None:
            print(f"Skipping {device_name} - no snapshot defined yet")
            continue
        device_id, sku = DEVICES[device_name]
        success = trigger_snapshot(device_id, sku, value)
        print(f"OK {device_name} -> {holiday}" if success else f"FAIL {device_name} -> {holiday}")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python3 govee_holiday.py <holiday>")
        sys.exit(1)
    apply_holiday(sys.argv[1])
