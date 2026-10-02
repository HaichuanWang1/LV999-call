"""解析 AndroidX DataStore 的 preferences_pb，把每个 key 的值打出来。

用不着 protobuf 库：preferences_pb 的 schema 就两层，手写 wire format 解析足够。
  PreferenceMap { map<string, Value> preferences = 1; }
  Value { oneof { bool boolean = 1; float float = 2; int32 integer = 3;
                  int64 long = 4; string string = 5; StringSet string_set = 6;
                  double double = 7; bytes bytes = 8; } }
"""
import sys


def read_varint(buf, i):
    result = 0
    shift = 0
    while True:
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    return result, i


def parse_fields(buf):
    i = 0
    out = []
    while i < len(buf):
        key, i = read_varint(buf, i)
        field, wire = key >> 3, key & 7
        if wire == 0:
            val, i = read_varint(buf, i)
        elif wire == 2:
            ln, i = read_varint(buf, i)
            val = buf[i:i + ln]
            i += ln
        elif wire == 5:
            val = buf[i:i + 4]
            i += 4
        elif wire == 1:
            val = buf[i:i + 8]
            i += 8
        else:
            raise ValueError(f"unsupported wire type {wire} at offset {i}")
        out.append((field, wire, val))
    return out


def parse_value(buf):
    for field, _wire, val in parse_fields(buf):
        if field == 1:
            return f"bool {bool(val)}"
        if field == 2:
            return f"float raw={val.hex()}"
        if field == 3:
            return f"int {val}"
        if field == 4:
            return f"long {val}"
        if field == 5:
            return f"string {val.decode('utf-8', 'replace')!r}"
        if field == 6:
            return "string_set"
        if field == 7:
            return f"double raw={val.hex()}"
        if field == 8:
            return f"bytes {len(val)}B"
    return "(empty)"


def main():
    data = open(sys.argv[1], "rb").read()
    rows = []
    for field, wire, val in parse_fields(data):
        if field == 1 and wire == 2:
            key, value = None, None
            for f2, _w2, v2 in parse_fields(val):
                if f2 == 1:
                    key = v2.decode("utf-8", "replace")
                elif f2 == 2:
                    value = parse_value(v2)
            rows.append((key, value))
    for key, value in sorted(rows, key=lambda r: r[0] or ""):
        print(f"{key} = {value}")
    print(f"--- 共 {len(rows)} 个 key ---")


if __name__ == "__main__":
    main()
