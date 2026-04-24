#!/usr/bin/env python3
"""RTSP probe script for local PC debugging."""

from __future__ import annotations

import argparse
import base64
import select
import socket
import struct
import sys
import time
from dataclasses import dataclass
from typing import Iterable
from urllib.parse import urljoin, urlparse, urlunparse


DEFAULT_TIMEOUT_SECONDS = 5.0
DEFAULT_CAPTURE_SECONDS = 8.0
USER_AGENT = "LUBIE-RTSP-Probe/1.0"


@dataclass
class RtspResponse:
    """Summary: Stores one RTSP response.

    Param status_code: Numeric RTSP status code.
    Param reason: RTSP reason phrase.
    Param headers: Response headers mapped by lowercase name.
    Param body: Raw response body bytes.
    Return: Immutable RTSP response snapshot.
    """

    status_code: int
    reason: str
    headers: dict[str, str]
    body: bytes


@dataclass
class MediaTrack:
    """Summary: Stores one SDP media track.

    Param media: Media kind such as video or audio.
    Param control: SDP control attribute when present.
    Param codec_hint: Human-readable codec hint from rtpmap or fmtp.
    Param index: Track index in SDP order.
    Return: Immutable SDP media descriptor.
    """

    media: str
    control: str | None
    codec_hint: str | None
    index: int


@dataclass
class TcpInterleavedSetup:
    """Summary: Stores one TCP interleaved SETUP result.

    Param track: SDP track used for SETUP.
    Param channels: RTP/RTCP interleaved channel numbers.
    Return: Immutable TCP track transport descriptor.
    """

    track: MediaTrack
    channels: tuple[int, int]


@dataclass
class UdpTrackSockets:
    """Summary: Stores one UDP RTP/RTCP socket pair for a track.

    Param track: SDP track used for SETUP.
    Param rtp_socket: Bound RTP UDP socket.
    Param rtcp_socket: Bound RTCP UDP socket.
    Return: Immutable UDP track socket descriptor.
    """

    track: MediaTrack
    rtp_socket: socket.socket
    rtcp_socket: socket.socket


class RtspBufferedSocket:
    """Summary: Adds buffered reads on top of a TCP RTSP socket.

    Param raw_socket: Connected TCP socket.
    Return: Helper that can parse RTSP responses and interleaved frames.
    """

    def __init__(self, raw_socket: socket.socket) -> None:
        self.raw_socket = raw_socket
        self.buffer = bytearray()

    def _recv_into_buffer(self) -> bool:
        """Summary: Receives one TCP chunk into the internal buffer.

        Param none: No parameters.
        Return: True when bytes were received, otherwise False on EOF.
        """

        chunk = self.raw_socket.recv(4096)
        if not chunk:
            return False
        self.buffer.extend(chunk)
        return True

    def read_exact(self, size: int) -> bytes:
        """Summary: Reads an exact byte count from the RTSP socket.

        Param size: Number of bytes to read.
        Return: Bytes with the requested size.
        """

        while len(self.buffer) < size:
            if not self._recv_into_buffer():
                raise ConnectionError("RTSP socket closed while reading exact bytes")
        result = bytes(self.buffer[:size])
        del self.buffer[:size]
        return result

    def read_until(self, delimiter: bytes) -> bytes:
        """Summary: Reads from the socket until the delimiter is found.

        Param delimiter: Delimiter bytes that terminate the read.
        Return: Bytes including the delimiter.
        """

        while True:
            position = self.buffer.find(delimiter)
            if position >= 0:
                end = position + len(delimiter)
                result = bytes(self.buffer[:end])
                del self.buffer[:end]
                return result
            if not self._recv_into_buffer():
                raise ConnectionError("RTSP socket closed before delimiter was received")

    def peek_first_byte(self) -> bytes:
        """Summary: Peeks one byte without consuming it.

        Param none: No parameters.
        Return: The first buffered byte.
        """

        while not self.buffer:
            if not self._recv_into_buffer():
                raise ConnectionError("RTSP socket closed while peeking")
        return bytes(self.buffer[:1])


def log(message: str) -> None:
    """Summary: Prints one timestamped log line.

    Param message: Human-readable log message.
    Return: Unit.
    """

    timestamp = time.strftime("%H:%M:%S")
    rendered = f"[{timestamp}] {message}\n"
    try:
        sys.stdout.write(rendered)
    except UnicodeEncodeError:
        sys.stdout.buffer.write(rendered.encode(sys.stdout.encoding or "utf-8", errors="backslashreplace"))
    sys.stdout.flush()


def parse_args() -> argparse.Namespace:
    """Summary: Parses CLI arguments for the RTSP probe script.

    Param none: No parameters.
    Return: Parsed command-line namespace.
    """

    parser = argparse.ArgumentParser(
        description="Probe an RTSP stream and print request, response, SDP, and RTP transport logs.",
    )
    parser.add_argument("url", help="RTSP URL such as rtsp://192.168.1.48:8554/fpv")
    parser.add_argument(
        "--transport",
        choices=("tcp", "udp"),
        default="tcp",
        help="Preferred RTP transport for SETUP. Default: tcp.",
    )
    parser.add_argument(
        "--media",
        choices=("video", "audio", "all"),
        default="all",
        help="Which SDP tracks to SETUP. Default: all.",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=DEFAULT_TIMEOUT_SECONDS,
        help=f"Socket timeout in seconds. Default: {DEFAULT_TIMEOUT_SECONDS}.",
    )
    parser.add_argument(
        "--duration",
        type=float,
        default=DEFAULT_CAPTURE_SECONDS,
        help=f"Seconds to capture RTP packets after PLAY. Default: {DEFAULT_CAPTURE_SECONDS}.",
    )
    parser.add_argument(
        "--show-sdp",
        action="store_true",
        help="Print the full SDP body returned by DESCRIBE.",
    )
    return parser.parse_args()


def normalized_rtsp_url(url: str) -> str:
    """Summary: Normalizes the RTSP URL without embedded credentials in logs.

    Param url: Raw RTSP URL.
    Return: Normalized RTSP URL string.
    """

    parsed = urlparse(url)
    if parsed.scheme.lower() != "rtsp":
        raise ValueError(f"Unsupported URL scheme: {parsed.scheme or '<empty>'}")
    if not parsed.hostname:
        raise ValueError("RTSP URL is missing a hostname")
    if parsed.port is not None and parsed.port <= 0:
        raise ValueError("RTSP URL contains an invalid port")
    if not parsed.path:
        raise ValueError("RTSP URL is missing a stream path")
    sanitized_netloc = parsed.hostname
    if parsed.port is not None:
        sanitized_netloc = f"{sanitized_netloc}:{parsed.port}"
    return urlunparse((parsed.scheme, sanitized_netloc, parsed.path, parsed.params, parsed.query, parsed.fragment))


def build_authorization_header(url: str) -> str | None:
    """Summary: Builds a Basic authorization header when credentials are embedded in the URL.

    Param url: RTSP URL that may contain username and password.
    Return: Basic authorization header value or None.
    """

    parsed = urlparse(url)
    if parsed.username is None or parsed.password is None:
        return None
    token = base64.b64encode(f"{parsed.username}:{parsed.password}".encode("utf-8")).decode("ascii")
    return f"Basic {token}"


def resolve_host_port(url: str) -> tuple[str, int]:
    """Summary: Resolves the destination host and port from an RTSP URL.

    Param url: RTSP URL string.
    Return: Hostname and port tuple.
    """

    parsed = urlparse(url)
    return parsed.hostname or "", parsed.port or 554


def connect_rtsp_socket(host: str, port: int, timeout: float) -> socket.socket:
    """Summary: Opens a TCP socket to the RTSP server.

    Param host: Target hostname or IP.
    Param port: Target RTSP TCP port.
    Param timeout: Socket timeout in seconds.
    Return: Connected TCP socket.
    """

    log(f"Resolving {host}:{port}")
    addresses = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    for family, socktype, proto, _, sockaddr in addresses:
        try:
            candidate = socket.socket(family, socktype, proto)
            candidate.settimeout(timeout)
            candidate.connect(sockaddr)
            log(f"TCP connected to {sockaddr[0]}:{sockaddr[1]}")
            return candidate
        except OSError as exc:
            log(f"TCP connect failed for {sockaddr}: {exc}")
    raise ConnectionError(f"Unable to connect to {host}:{port}")


def read_rtsp_response(buffered_socket: RtspBufferedSocket) -> RtspResponse:
    """Summary: Reads one RTSP response from the buffered socket.

    Param buffered_socket: Buffered RTSP socket helper.
    Return: Parsed RTSP response.
    """

    header_block = buffered_socket.read_until(b"\r\n\r\n")
    header_text = header_block.decode("utf-8", errors="replace")
    header_lines = header_text.split("\r\n")
    status_line = header_lines[0]
    if not status_line.startswith("RTSP/1.0 "):
        raise ValueError(f"Unexpected RTSP status line: {status_line}")
    _, status_code_text, reason = status_line.split(" ", 2)
    headers: dict[str, str] = {}
    for raw_header in header_lines[1:]:
        if not raw_header:
            continue
        name, _, value = raw_header.partition(":")
        headers[name.strip().lower()] = value.strip()
    content_length = int(headers.get("content-length", "0") or "0")
    body = buffered_socket.read_exact(content_length) if content_length > 0 else b""
    return RtspResponse(status_code=int(status_code_text), reason=reason, headers=headers, body=body)


def send_rtsp_request(
    buffered_socket: RtspBufferedSocket,
    method: str,
    url: str,
    cseq: int,
    headers: dict[str, str] | None = None,
    body: bytes | None = None,
) -> RtspResponse:
    """Summary: Sends one RTSP request and reads the corresponding response.

    Param buffered_socket: Buffered RTSP socket helper.
    Param method: RTSP method name.
    Param url: RTSP request URL.
    Param cseq: RTSP CSeq number.
    Param headers: Additional request headers.
    Param body: Optional request body bytes.
    Return: Parsed RTSP response.
    """

    final_headers = {
        "CSeq": str(cseq),
        "User-Agent": USER_AGENT,
    }
    if headers:
        final_headers.update(headers)
    payload = body or b""
    if payload:
        final_headers["Content-Length"] = str(len(payload))
    request_lines = [f"{method} {url} RTSP/1.0"]
    request_lines.extend(f"{key}: {value}" for key, value in final_headers.items())
    request_lines.append("")
    request_lines.append("")
    request_bytes = "\r\n".join(request_lines).encode("utf-8") + payload
    log(f">>> {method} {url}")
    for key, value in final_headers.items():
        redacted_value = value
        if key.lower() == "authorization":
            redacted_value = "<redacted>"
        log(f">>> {key}: {redacted_value}")
    buffered_socket.raw_socket.sendall(request_bytes)
    response = read_rtsp_response(buffered_socket)
    log(f"<<< RTSP/1.0 {response.status_code} {response.reason}")
    for key, value in response.headers.items():
        log(f"<<< {key}: {value}")
    if response.body:
        log(f"<<< body: {len(response.body)} bytes")
    return response


def parse_sdp_tracks(sdp_text: str) -> list[MediaTrack]:
    """Summary: Parses SDP media sections into track descriptors.

    Param sdp_text: SDP text returned by DESCRIBE.
    Return: Parsed media tracks in SDP order.
    """

    tracks: list[MediaTrack] = []
    current_media: str | None = None
    current_control: str | None = None
    current_codec_hint: str | None = None
    index = -1
    for raw_line in sdp_text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith("m="):
            if current_media is not None:
                tracks.append(
                    MediaTrack(
                        media=current_media,
                        control=current_control,
                        codec_hint=current_codec_hint,
                        index=index,
                    )
                )
            index += 1
            current_media = line[2:].split(" ", 1)[0]
            current_control = None
            current_codec_hint = None
        elif line.startswith("a=control:"):
            current_control = line[len("a=control:") :]
        elif line.startswith("a=rtpmap:"):
            current_codec_hint = line[len("a=rtpmap:") :]
        elif line.startswith("a=fmtp:") and current_codec_hint is None:
            current_codec_hint = line[len("a=fmtp:") :]
    if current_media is not None:
        tracks.append(
            MediaTrack(
                media=current_media,
                control=current_control,
                codec_hint=current_codec_hint,
                index=index,
            )
        )
    return tracks


def select_tracks(tracks: Iterable[MediaTrack], media_filter: str) -> list[MediaTrack]:
    """Summary: Filters SDP tracks based on the CLI media selection.

    Param tracks: All parsed SDP tracks.
    Param media_filter: Requested media filter.
    Return: Filtered track list.
    """

    if media_filter == "all":
        return list(tracks)
    return [track for track in tracks if track.media == media_filter]


def resolve_control_url(base_url: str, content_base: str | None, control: str | None) -> str:
    """Summary: Resolves one SDP control attribute into an absolute RTSP URL.

    Param base_url: Original RTSP URL.
    Param content_base: Content-Base header from DESCRIBE when present.
    Param control: SDP control attribute.
    Return: Absolute RTSP control URL.
    """

    if not control or control == "*":
        return content_base or base_url
    if control.startswith("rtsp://"):
        return control
    return urljoin((content_base or base_url).rstrip("/") + "/", control)


def extract_session_id(session_header: str | None) -> str | None:
    """Summary: Extracts the RTSP session identifier from a Session header value.

    Param session_header: Raw Session header value.
    Return: Session identifier or None.
    """

    if not session_header:
        return None
    return session_header.split(";", 1)[0].strip()


def allocate_udp_pair(timeout: float) -> tuple[socket.socket, socket.socket]:
    """Summary: Allocates an even RTP port and the following RTCP port on localhost.

    Param timeout: Socket timeout in seconds.
    Return: RTP and RTCP UDP sockets bound to consecutive ports.
    """

    for _ in range(20):
        rtp_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        rtp_socket.bind(("0.0.0.0", 0))
        rtp_port = rtp_socket.getsockname()[1]
        if rtp_port % 2 == 1:
            rtp_socket.close()
            continue
        rtcp_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            rtcp_socket.bind(("0.0.0.0", rtp_port + 1))
        except OSError:
            rtp_socket.close()
            rtcp_socket.close()
            continue
        rtp_socket.settimeout(timeout)
        rtcp_socket.settimeout(timeout)
        return rtp_socket, rtcp_socket
    raise OSError("Unable to allocate consecutive UDP RTP/RTCP ports")


def parse_rtp_packet(packet: bytes) -> str:
    """Summary: Parses basic RTP header fields for logging.

    Param packet: Raw RTP packet bytes.
    Return: Human-readable RTP header summary.
    """

    if len(packet) < 12:
        return f"payload={len(packet)} bytes (too short for RTP header)"
    version = packet[0] >> 6
    payload_type = packet[1] & 0x7F
    sequence = struct.unpack("!H", packet[2:4])[0]
    timestamp = struct.unpack("!I", packet[4:8])[0]
    ssrc = struct.unpack("!I", packet[8:12])[0]
    return (
        f"version={version} payloadType={payload_type} "
        f"seq={sequence} ts={timestamp} ssrc=0x{ssrc:08X} "
        f"packetBytes={len(packet)}"
    )


def run_tcp_interleaved_capture(
    buffered_socket: RtspBufferedSocket,
    channel_map: dict[int, str],
    capture_seconds: float,
) -> None:
    """Summary: Reads TCP interleaved RTP packets for the requested duration.

    Param buffered_socket: Connected RTSP buffered socket.
    Param channel_map: Mapping from interleaved channel number to track label.
    Param capture_seconds: Capture duration in seconds.
    Return: Unit.
    """

    deadline = time.monotonic() + capture_seconds
    packet_count = 0
    bytes_by_channel: dict[int, int] = {channel: 0 for channel in channel_map}
    log(f"Capturing interleaved RTP for {capture_seconds:.1f}s")
    while time.monotonic() < deadline:
        remaining = max(0.1, deadline - time.monotonic())
        buffered_socket.raw_socket.settimeout(min(remaining, 1.0))
        try:
            first_byte = buffered_socket.peek_first_byte()
        except socket.timeout:
            continue
        if first_byte == b"$":
            buffered_socket.read_exact(1)
            channel = buffered_socket.read_exact(1)[0]
            packet_length = struct.unpack("!H", buffered_socket.read_exact(2))[0]
            payload = buffered_socket.read_exact(packet_length)
            bytes_by_channel[channel] = bytes_by_channel.get(channel, 0) + len(payload)
            packet_count += 1
            track_label = channel_map.get(channel, f"channel-{channel}")
            log(f"RTP/TCP {track_label}: {parse_rtp_packet(payload)}")
        else:
            response = read_rtsp_response(buffered_socket)
            log(f"Extra RTSP response during capture: {response.status_code} {response.reason}")
    log(f"TCP capture done. packets={packet_count}")
    for channel, total_bytes in sorted(bytes_by_channel.items()):
        log(f"TCP channel {channel} bytes={total_bytes} label={channel_map.get(channel, 'unknown')}")


def run_udp_capture(
    udp_pairs: list[UdpTrackSockets],
    capture_seconds: float,
) -> None:
    """Summary: Reads RTP and RTCP UDP packets for the requested duration.

    Param udp_pairs: UDP socket pairs created during SETUP.
    Param capture_seconds: Capture duration in seconds.
    Return: Unit.
    """

    deadline = time.monotonic() + capture_seconds
    packet_count = 0
    sockets_to_label: dict[socket.socket, str] = {}
    for pair in udp_pairs:
        sockets_to_label[pair.rtp_socket] = f"{pair.track.media}[{pair.track.index}] RTP"
        sockets_to_label[pair.rtcp_socket] = f"{pair.track.media}[{pair.track.index}] RTCP"
    log(f"Capturing UDP RTP/RTCP for {capture_seconds:.1f}s")
    while time.monotonic() < deadline:
        remaining = max(0.1, deadline - time.monotonic())
        readable, _, _ = select.select(list(sockets_to_label.keys()), [], [], min(remaining, 1.0))
        for udp_socket in readable:
            packet, address = udp_socket.recvfrom(65535)
            packet_count += 1
            label = sockets_to_label[udp_socket]
            if label.endswith("RTP"):
                log(f"UDP {label} from {address[0]}:{address[1]} {parse_rtp_packet(packet)}")
            else:
                log(f"UDP {label} from {address[0]}:{address[1]} bytes={len(packet)}")
    log(f"UDP capture done. packets={packet_count}")


def run_probe(arguments: argparse.Namespace) -> int:
    """Summary: Runs the full RTSP OPTIONS/DESCRIBE/SETUP/PLAY probe flow.

    Param arguments: Parsed CLI arguments.
    Return: Process exit code.
    """

    rtsp_url = normalized_rtsp_url(arguments.url)
    authorization_header = build_authorization_header(arguments.url)
    host, port = resolve_host_port(arguments.url)
    cseq = 1
    tcp_socket = connect_rtsp_socket(host, port, arguments.timeout)
    buffered_socket = RtspBufferedSocket(tcp_socket)
    udp_pairs: list[UdpTrackSockets] = []
    try:
        base_headers = {}
        if authorization_header:
            base_headers["Authorization"] = authorization_header

        options_response = send_rtsp_request(
            buffered_socket,
            method="OPTIONS",
            url=rtsp_url,
            cseq=cseq,
            headers=base_headers,
        )
        cseq += 1
        if options_response.status_code != 200:
            return 1

        describe_headers = {
            **base_headers,
            "Accept": "application/sdp",
        }
        describe_response = send_rtsp_request(
            buffered_socket,
            method="DESCRIBE",
            url=rtsp_url,
            cseq=cseq,
            headers=describe_headers,
        )
        cseq += 1
        if describe_response.status_code != 200:
            return 1

        sdp_text = describe_response.body.decode("utf-8", errors="replace")
        log("--- SDP Tracks ---")
        if arguments.show_sdp:
            print(sdp_text)
        content_base = describe_response.headers.get("content-base") or describe_response.headers.get("content-location")
        all_tracks = parse_sdp_tracks(sdp_text)
        if not all_tracks:
            log("No SDP media tracks found.")
            return 1
        for track in all_tracks:
            control_url = resolve_control_url(rtsp_url, content_base, track.control)
            log(
                f"track index={track.index} media={track.media} "
                f"control={track.control or '<none>'} resolved={control_url} "
                f"codec={track.codec_hint or '<unknown>'}"
            )
        selected_tracks = select_tracks(all_tracks, arguments.media)
        if not selected_tracks:
            log(f"No SDP tracks match --media={arguments.media}")
            return 1

        session_id: str | None = None
        tcp_setups: list[TcpInterleavedSetup] = []
        for index, track in enumerate(selected_tracks):
            control_url = resolve_control_url(rtsp_url, content_base, track.control)
            setup_headers = dict(base_headers)
            if session_id:
                setup_headers["Session"] = session_id
            if arguments.transport == "tcp":
                channels = (index * 2, index * 2 + 1)
                setup_headers["Transport"] = (
                    f"RTP/AVP/TCP;unicast;interleaved={channels[0]}-{channels[1]}"
                )
                response = send_rtsp_request(
                    buffered_socket,
                    method="SETUP",
                    url=control_url,
                    cseq=cseq,
                    headers=setup_headers,
                )
                cseq += 1
                if response.status_code != 200:
                    return 1
                session_id = extract_session_id(response.headers.get("session")) or session_id
                tcp_setups.append(TcpInterleavedSetup(track=track, channels=channels))
            else:
                rtp_socket, rtcp_socket = allocate_udp_pair(arguments.timeout)
                rtp_port = rtp_socket.getsockname()[1]
                rtcp_port = rtcp_socket.getsockname()[1]
                setup_headers["Transport"] = f"RTP/AVP;unicast;client_port={rtp_port}-{rtcp_port}"
                response = send_rtsp_request(
                    buffered_socket,
                    method="SETUP",
                    url=control_url,
                    cseq=cseq,
                    headers=setup_headers,
                )
                cseq += 1
                if response.status_code != 200:
                    rtp_socket.close()
                    rtcp_socket.close()
                    return 1
                session_id = extract_session_id(response.headers.get("session")) or session_id
                udp_pairs.append(
                    UdpTrackSockets(
                        track=track,
                        rtp_socket=rtp_socket,
                        rtcp_socket=rtcp_socket,
                    )
                )

        if not session_id:
            log("No Session header returned by SETUP.")
            return 1

        play_headers = {
            **base_headers,
            "Session": session_id,
            "Range": "npt=0.000-",
        }
        play_response = send_rtsp_request(
            buffered_socket,
            method="PLAY",
            url=rtsp_url,
            cseq=cseq,
            headers=play_headers,
        )
        cseq += 1
        if play_response.status_code != 200:
            return 1

        if arguments.transport == "tcp":
            channel_map: dict[int, str] = {}
            for setup in tcp_setups:
                channel_map[setup.channels[0]] = f"{setup.track.media}[{setup.track.index}] RTP"
                channel_map[setup.channels[1]] = f"{setup.track.media}[{setup.track.index}] RTCP"
            run_tcp_interleaved_capture(buffered_socket, channel_map, arguments.duration)
        else:
            run_udp_capture(udp_pairs, arguments.duration)

        teardown_headers = {
            **base_headers,
            "Session": session_id,
        }
        try:
            send_rtsp_request(
                buffered_socket,
                method="TEARDOWN",
                url=rtsp_url,
                cseq=cseq,
                headers=teardown_headers,
            )
        except Exception as exc:  # noqa: BLE001
            log(f"TEARDOWN failed: {exc}")
        return 0
    except socket.timeout:
        log("Socket timeout while waiting for RTSP or RTP data.")
        return 2
    except Exception as exc:  # noqa: BLE001
        log(f"Probe failed: {exc}")
        return 3
    finally:
        for udp_pair in udp_pairs:
            udp_pair.rtp_socket.close()
            udp_pair.rtcp_socket.close()
        tcp_socket.close()


def main() -> int:
    """Summary: Program entry point for the RTSP probe CLI.

    Param none: No parameters.
    Return: Process exit code.
    """

    try:
        arguments = parse_args()
        return run_probe(arguments)
    except KeyboardInterrupt:
        log("Interrupted by user.")
        return 130
    except Exception as exc:  # noqa: BLE001
        log(f"Fatal error: {exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
