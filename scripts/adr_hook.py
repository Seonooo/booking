import sys
import json
import re

try:
    data = json.load(sys.stdin)
    file_path = data.get('tool_input', {}).get('file_path', '').replace('\\', '/')
    match = re.search(r'docs/adr/(ADR-\d+)', file_path)
    if match:
        adr_id = match.group(1)
        print(json.dumps({
            "systemMessage": f"ADR 수정 감지: {adr_id} 파일이 변경되었습니다. /adr-sync {adr_id} 를 실행해 CLAUDE.md, ARCHITECTURE.md, DECISIONS.md 등 연관 문서를 점검하세요."
        }))
except Exception:
    pass
