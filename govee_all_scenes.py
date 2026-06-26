import urllib.request
import json

API_KEY = "e5cfab60-957e-4407-838c-62de0a1480d9"

DEVICES = [
    ("1C:AB:D3:32:34:39:45:05", "H7039", "Fence Lights"),
    ("1A:21:D2:2D:44:46:06:3C", "H7039", "Pergola Lights"),
    ("D8:86:D9:38:30:33:72:53", "H705A", "Playset Lights"),
]

req = urllib.request.Request(
    "https://openapi.api.govee.com/router/api/v1/user/devices",
    headers={"Govee-API-Key": API_KEY, "Content-Type": "application/json"}
)
with urllib.request.urlopen(req) as resp:
    data = json.loads(resp.read().decode())

for dev_id, sku, name in DEVICES:
    print(f"\n{'='*50}")
    print(f"Device: {name}")
    print('='*50)
    for device in data.get("data", []):
        if device["device"] == dev_id:
            for cap in device.get("capabilities", []):
                if cap["instance"] in ["snapshot", "diyScene", "lightScene"]:
                    print(f"\n--- {cap['instance']} ---")
                    for opt in cap["parameters"].get("options", []):
                        print(f"  name: {opt['name']!r:40} value: {opt['value']}")
