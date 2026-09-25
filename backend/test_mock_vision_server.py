from mock_vision_server import jpeg_dimensions


def test_jpeg_dimensions():
    image = bytes.fromhex("ffd8ffc000080801e002d00301ffd9")
    assert jpeg_dimensions(image) == (720, 480)
    try:
        jpeg_dimensions(image[:-2])
    except ValueError:
        pass
    else:
        raise AssertionError("truncated JPEG accepted")


if __name__ == "__main__":
    test_jpeg_dimensions()
