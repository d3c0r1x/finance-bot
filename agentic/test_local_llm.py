import io
import json
from unittest.mock import patch

from agentic.local_llm import LocalLLMConfig, chat, parse_json_or_summary
from agentic.local_worker import build_prompt


class FakeResponse:
    def __init__(self, payload):
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self):
        return json.dumps(self.payload).encode("utf-8")


def test_chat_reads_openai_compatible_response():
    payload = {"choices": [{"message": {"content": "ok"}}]}
    with patch("urllib.request.urlopen", return_value=FakeResponse(payload)) as urlopen:
        result = chat(
            [{"role": "user", "content": "ping"}],
            config=LocalLLMConfig(base_url="http://127.0.0.1:8080/v1", model="bonsai-2", timeout_seconds=1),
        )
    assert result == "ok"
    request = urlopen.call_args.args[0]
    assert request.full_url == "http://127.0.0.1:8080/v1/chat/completions"
    sent = json.loads(request.data.decode("utf-8"))
    assert sent["model"] == "bonsai-2"


def test_parse_json_or_summary_accepts_json_and_wraps_text():
    parsed = parse_json_or_summary('{"summary":"done","findings":[]}')
    assert parsed["summary"] == "done"
    wrapped = parse_json_or_summary("plain model answer")
    assert wrapped["summary"] == "plain model answer"
    assert wrapped["recommended_actions"] == []


def test_build_prompt_includes_files_and_respects_budget(tmp_path):
    path = tmp_path / "sample.py"
    path.write_text("abcdef", encoding="utf-8")
    prompt = build_prompt("review", "task", [path], max_chars=3)
    assert "Mode: review" in prompt
    assert str(path) in prompt
    assert "abc" in prompt
    assert "TRUNCATED" in prompt

