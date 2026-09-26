import base64
import io
import json
from unittest.mock import patch

from mock_vision_server import analyze_image, jpeg_dimensions


def test_jpeg_dimensions():
    image = bytes.fromhex("ffd8ffc000080801e002d00301ffd9")
    assert jpeg_dimensions(image) == (720, 480)
    try:
        jpeg_dimensions(image[:-2])
    except ValueError:
        pass
    else:
        raise AssertionError("truncated JPEG accepted")


def test_qwen_request_shape():
    image = bytes.fromhex("ffd8ffc000080801e002d00301ffd9")
    response = {"choices": [{"message": {"content": "Two monitors."}}],
                "timings": {"prompt_ms": 12.5, "predicted_ms": 3.5}}

    def fake_urlopen(request, timeout):
        assert request.full_url == "http://vlm:8081/v1/chat/completions"
        assert timeout == 120
        body = json.loads(request.data)
        assert body["model"] == "qwen-vl"
        assert body["messages"][0]["content"] == [
            {"type": "text", "text": "Count monitors"},
            {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + base64.b64encode(image).decode()}},
        ]
        return io.BytesIO(json.dumps(response).encode())

    with patch("mock_vision_server.urlopen", fake_urlopen):
        assert analyze_image("http://vlm:8081", "qwen-vl", "Count monitors", image) == {
            "answer": "Two monitors.", "model_ms": 16.0}


if __name__ == "__main__":
    test_jpeg_dimensions()
    test_qwen_request_shape()
