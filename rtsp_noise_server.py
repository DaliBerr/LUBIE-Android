#!/usr/bin/env python3
"""Run a local RTSP server backed by FFmpeg-generated RTP H.264 video."""

from __future__ import annotations

import argparse
import base64
import os
import re
import shlex
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


DEFAULT_RTP_PORT = 5004
MAX_UDP_PACKET_SIZE = 65535
VIDEO_EXTENSIONS = {".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v"}


def parse_args() -> argparse.Namespace:
    """
    Summary: Parse command-line options for the local RTSP video server.
    Param: none.
    Return: Parsed argparse namespace.
    """
    parser = argparse.ArgumentParser(
        description="Run a local RTSP video server with Android-friendly H.264 settings.",
    )
    parser.add_argument(
        "--ffmpeg-bin",
        default="ffmpeg",
        help="FFmpeg executable path. Default: ffmpeg",
    )
    parser.add_argument(
        "--listen-host",
        default="0.0.0.0",
        help="RTSP server listen host. Default: 0.0.0.0",
    )
    parser.add_argument(
        "--advertise-host",
        default=None,
        help="Host/IP printed for Android clients. Default: auto-detect local LAN IP",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=8554,
        help="RTSP server listen port. Default: 8554",
    )
    parser.add_argument(
        "--path",
        default="test",
        help="RTSP path without leading slash. Default: test",
    )
    parser.add_argument(
        "--width",
        type=int,
        default=1280,
        help="Output width after server-side scaling/padding. Default: 1280",
    )
    parser.add_argument(
        "--height",
        type=int,
        default=720,
        help="Output height after server-side scaling/padding. Default: 720",
    )
    parser.add_argument(
        "--fps",
        type=int,
        default=30,
        help="Output frames per second after server-side CFR conversion. Default: 30",
    )
    parser.add_argument(
        "--source",
        choices=("auto", "file", "noise"),
        default="auto",
        help="Video source mode. auto prefers a local video file when available, otherwise falls back to noise. Default: auto",
    )
    parser.add_argument(
        "--input-file",
        default=None,
        help="Optional local video file used as the RTSP source. Default: auto-detect test.mp4 in the repo root",
    )
    parser.add_argument(
        "--loop-input",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Whether to loop the local input video indefinitely. Default: enabled",
    )
    parser.add_argument(
        "--noise",
        type=int,
        default=72,
        help="Noise strength passed to FFmpeg noise filter. Default: 72",
    )
    parser.add_argument(
        "--profile",
        choices=("baseline", "main"),
        default="baseline",
        help="H.264 profile for Android compatibility. Default: baseline",
    )
    parser.add_argument(
        "--preset",
        default="ultrafast",
        help="libx264 preset. Default: ultrafast",
    )
    parser.add_argument(
        "--gop",
        type=int,
        default=30,
        help="Keyframe interval in frames. Default: 30",
    )
    parser.add_argument(
        "--video-bitrate",
        default="4M",
        help="Target/max video bitrate for the outgoing H.264 stream. Default: 4M",
    )
    parser.add_argument(
        "--rtp-port",
        type=int,
        default=DEFAULT_RTP_PORT,
        help="Local UDP RTP port used between FFmpeg and the embedded RTSP relay. Default: 5004",
    )
    parser.add_argument(
        "--ffplay-bin",
        default="ffplay",
        help="FFplay executable path used for local preview. Default: ffplay",
    )
    parser.add_argument(
        "--preview",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Whether to launch a local ffplay preview window. Default: enabled",
    )
    parser.add_argument(
        "--preview-title",
        default="LUBIE RTSP Preview",
        help="Window title for the local ffplay preview. Default: LUBIE RTSP Preview",
    )
    return parser.parse_args()


def detect_advertise_host() -> str:
    """
    Summary: Best-effort detect the local LAN IP to print for Android clients.
    Param: none.
    Return: IP string or localhost fallback.
    """
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("8.8.8.8", 80))
        detected_ip = probe.getsockname()[0]
        if detected_ip:
            return detected_ip
    except OSError:
        pass
    finally:
        probe.close()
    return "127.0.0.1"


def build_ffmpeg_command(
    args: argparse.Namespace,
    sdp_path: Path,
    input_file: Path | None,
) -> list[str]:
    """
    Summary: Build the FFmpeg command line that generates RTP H.264 packets.
    Param: args Parsed script arguments.
    Param: sdp_path Temporary SDP output path.
    Param: input_file Optional local video source path.
    Return: Command list suitable for subprocess.
    """
    rtp_target = f"rtp://127.0.0.1:{args.rtp_port}?pkt_size=1200"
    video_filter = (
        f"fps={args.fps},"
        f"scale={args.width}:{args.height}:force_original_aspect_ratio=decrease,"
        f"pad={args.width}:{args.height}:(ow-iw)/2:(oh-ih)/2:color=black,"
        "setsar=1,"
        "format=yuv420p"
    )
    command = [
        args.ffmpeg_bin,
        "-hide_banner",
        "-loglevel",
        "info",
        "-re",
    ]
    if input_file is None:
        filter_graph = (
            f"nullsrc=size={args.width}x{args.height}:rate={args.fps},"
            f"noise=alls={args.noise}:allf=t+u,"
            "format=yuv420p"
        )
        command.extend(
            [
                "-f",
                "lavfi",
                "-i",
                filter_graph,
            ],
        )
    else:
        if args.loop_input:
            command.extend(["-stream_loop", "-1"])
        command.extend(
            [
                "-fflags",
                "+genpts",
                "-i",
                str(input_file),
            ],
        )
    command.extend(
        [
            "-map",
            "0:v:0",
            "-vf",
            video_filter,
            "-an",
            "-sn",
            "-dn",
        ],
    )
    command.extend(
        [
        "-c:v",
        "libx264",
        "-preset",
        args.preset,
        "-tune",
        "zerolatency",
        "-flags",
        "+global_header",
        "-pix_fmt",
        "yuv420p",
        "-profile:v",
        args.profile,
        "-level:v",
        "3.1",
        "-g",
        str(args.gop),
        "-keyint_min",
        str(args.gop),
        "-sc_threshold",
        "0",
        "-b:v",
        args.video_bitrate,
        "-maxrate",
        args.video_bitrate,
        "-bufsize",
        args.video_bitrate,
        "-x264-params",
        "repeat-headers=1:nal-hrd=none:bframes=0",
        "-payload_type",
        "96",
        "-f",
        "rtp",
        "-sdp_file",
        str(sdp_path),
        rtp_target,
        ],
    )
    return command


def detect_default_input_file(search_root: Path) -> Path | None:
    """
    Summary: Find a sensible default local video file near the script.
    Param: search_root Directory scanned for candidate video files.
    Return: Resolved video path or None when no obvious candidate exists.
    """
    preferred_path = search_root / "test.mp4"
    if preferred_path.is_file():
        return preferred_path.resolve()

    candidates = sorted(
        file_path.resolve()
        for file_path in search_root.iterdir()
        if file_path.is_file() and file_path.suffix.lower() in VIDEO_EXTENSIONS
    )
    if len(candidates) == 1:
        return candidates[0]
    return None


def resolve_input_file(args: argparse.Namespace, search_root: Path) -> Path | None:
    """
    Summary: Resolve which video file should be used for the RTSP source.
    Param: args Parsed script arguments.
    Param: search_root Directory used for auto-detect.
    Return: Resolved input file path or None when synthetic noise should be used.
    """
    requested_path = Path(args.input_file).expanduser().resolve() if args.input_file else None
    if requested_path is not None and not requested_path.is_file():
        raise RuntimeError(f"Input video file was not found: {requested_path}")

    if args.source == "noise":
        return None
    if args.source == "file":
        if requested_path is None:
            auto_detected_path = detect_default_input_file(search_root)
            if auto_detected_path is None:
                raise RuntimeError(
                    "Source mode 'file' was requested, but no input video file was provided and no default video could be auto-detected.",
                )
            return auto_detected_path
        return requested_path

    if requested_path is not None:
        return requested_path
    return detect_default_input_file(search_root)


def build_ffplay_command(
    ffplay_bin: str,
    preview_url: str,
    window_title: str,
) -> list[str]:
    """
    Summary: Build the FFplay command used for the optional local preview window.
    Param: ffplay_bin FFplay executable path.
    Param: preview_url RTSP URL opened by FFplay.
    Param: window_title Desired preview window title.
    Return: Command list suitable for subprocess.
    """
    return [
        ffplay_bin,
        "-hide_banner",
        "-loglevel",
        "warning",
        "-fflags",
        "nobuffer",
        "-flags",
        "low_delay",
        "-framedrop",
        "-sync",
        "video",
        "-window_title",
        window_title,
        "-rtsp_transport",
        "tcp",
        preview_url,
    ]


def quote_command(command: Iterable[str]) -> str:
    """
    Summary: Render a shell-friendly command preview for logging.
    Param: command Iterable command arguments.
    Return: Joined command string.
    """
    return " ".join(shlex.quote(part) for part in command)


def wait_for_sdp_file(sdp_path: Path, timeout_seconds: float = 5.0) -> str:
    """
    Summary: Wait until FFmpeg writes the SDP file and return its contents.
    Param: sdp_path SDP file path.
    Param: timeout_seconds Maximum wait time.
    Return: SDP file contents.
    """
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if sdp_path.exists() and sdp_path.stat().st_size > 0:
            return sdp_path.read_text(encoding="utf-8", errors="replace")
        time.sleep(0.05)
    raise RuntimeError(f"Timed out waiting for SDP file: {sdp_path}")


def build_rtsp_sdp(
    raw_sdp: str,
    profile_level_id: str | None = None,
    sps_base64: str | None = None,
    pps_base64: str | None = None,
) -> str:
    """
    Summary: Adapt FFmpeg-generated RTP SDP for RTSP DESCRIBE responses.
    Param: raw_sdp Original SDP text from FFmpeg.
    Param: profile_level_id H264 profile-level-id string when available.
    Param: sps_base64 Base64 SPS payload when available.
    Param: pps_base64 Base64 PPS payload when available.
    Return: RTSP-friendly SDP text.
    """
    raw_lines = [line.strip() for line in raw_sdp.splitlines() if line.strip()]
    if not raw_lines:
        raise RuntimeError("FFmpeg produced an empty SDP file")

    output_lines: list[str] = []
    inserted_session_control = False
    inserted_track_control = False

    for line in raw_lines:
        if line.startswith("c="):
            output_lines.append("c=IN IP4 0.0.0.0")
            continue

        if line.startswith("m="):
            if not inserted_session_control:
                output_lines.append("a=control:*")
                inserted_session_control = True
            media_line = re.sub(r"^(m=\w+)\s+\d+", r"\1 0", line)
            output_lines.append(media_line)
            continue

        if line.startswith("a=fmtp:"):
            output_lines.append(
                enrich_h264_fmtp_line(
                    line,
                    profile_level_id=profile_level_id,
                    sps_base64=sps_base64,
                    pps_base64=pps_base64,
                ),
            )
            continue

        output_lines.append(line)

    for index, line in enumerate(output_lines):
        if line.startswith("a=control:") and line != "a=control:*":
            inserted_track_control = True
            break
        if line.startswith("m="):
            next_index = index + 1
            while next_index < len(output_lines) and not output_lines[next_index].startswith("m="):
                if output_lines[next_index].startswith("a=control:") and output_lines[next_index] != "a=control:*":
                    inserted_track_control = True
                    break
                next_index += 1
            if inserted_track_control:
                break

    if not inserted_track_control:
        last_media_index = -1
        for index, line in enumerate(output_lines):
            if line.startswith("m="):
                last_media_index = index
        if last_media_index >= 0:
            insert_at = last_media_index + 1
            while insert_at < len(output_lines) and not output_lines[insert_at].startswith("m="):
                insert_at += 1
            output_lines.insert(insert_at, "a=control:trackID=0")

    return "\r\n".join(output_lines) + "\r\n"


def enrich_h264_fmtp_line(
    original_fmtp_line: str,
    profile_level_id: str | None,
    sps_base64: str | None,
    pps_base64: str | None,
) -> str:
    """
    Summary: Enrich the H264 fmtp line with Android-friendly profile and parameter sets.
    Param: original_fmtp_line Original fmtp line from FFmpeg SDP.
    Param: profile_level_id H264 profile-level-id string when available.
    Param: sps_base64 Base64 SPS payload when available.
    Param: pps_base64 Base64 PPS payload when available.
    Return: Updated fmtp line.
    """
    if profile_level_id is None or sps_base64 is None or pps_base64 is None:
        return original_fmtp_line

    prefix, _, params_text = original_fmtp_line.partition(" ")
    param_map: dict[str, str] = {}
    for raw_param in params_text.split(";"):
        raw_param = raw_param.strip()
        if not raw_param:
            continue
        if "=" in raw_param:
            key, value = raw_param.split("=", 1)
            param_map[key.strip()] = value.strip()
        else:
            param_map[raw_param] = ""

    param_map["packetization-mode"] = param_map.get("packetization-mode", "1") or "1"
    param_map["profile-level-id"] = profile_level_id
    param_map["sprop-parameter-sets"] = f"{sps_base64},{pps_base64}"

    ordered_keys = ["packetization-mode", "profile-level-id", "sprop-parameter-sets"]
    ordered_params = [f"{key}={param_map[key]}" for key in ordered_keys]
    for key, value in param_map.items():
        if key not in ordered_keys:
            ordered_params.append(f"{key}={value}" if value else key)
    return f"{prefix} {';'.join(ordered_params)}"


def stream_subprocess_logs(process: subprocess.Popen[str]) -> None:
    """
    Summary: Forward FFmpeg stdout/stderr lines to the console while it runs.
    Param: process Running FFmpeg subprocess.
    Return: None.
    """
    assert process.stdout is not None
    for raw_line in process.stdout:
        sys.stdout.write(f"[ffmpeg] {raw_line}")
        sys.stdout.flush()


def terminate_process(process: subprocess.Popen[str]) -> int:
    """
    Summary: Gracefully stop the FFmpeg subprocess and return its exit code.
    Param: process Running FFmpeg subprocess.
    Return: Final process exit code.
    """
    if process.poll() is not None:
        return process.returncode
    process.terminate()
    try:
        return process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        return process.wait(timeout=5)


@dataclass
class RtspClientState:
    """
    Summary: Track one active RTSP client session used by the relay.
    Param: session_id Logical RTSP session id.
    Param: socket Connected RTSP TCP socket.
    Param: address Client address tuple.
    Param: transport RTP transport mode, either tcp or udp.
    Param: rtp_channel Interleaved TCP channel for RTP packets.
    Param: rtcp_channel Interleaved TCP channel for RTCP packets.
    Param: udp_rtp_endpoint Client UDP RTP endpoint when transport is UDP.
    Param: udp_rtcp_endpoint Client UDP RTCP endpoint when transport is UDP.
    Param: playing Whether PLAY has been received.
    Return: Mutable RTSP client state container.
    """

    session_id: str
    socket: socket.socket
    address: tuple[str, int]
    transport: str = "tcp"
    rtp_channel: int = 0
    rtcp_channel: int = 1
    udp_rtp_endpoint: tuple[str, int] | None = None
    udp_rtcp_endpoint: tuple[str, int] | None = None
    playing: bool = False


class RtpTcpRelay:
    """
    Summary: Receive RTP packets from FFmpeg over UDP and relay them to RTSP clients.
    Return: Embedded RTP relay for active TCP-interleaved and UDP clients.
    """

    def __init__(
        self,
        udp_port: int,
        listen_host: str = "127.0.0.1",
    ) -> None:
        """
        Summary: Initialize the UDP listener and relay state.
        Param: udp_port Local UDP port receiving RTP from FFmpeg.
        Param: listen_host Bind host for the local UDP socket.
        Return: None.
        """
        self._lock = threading.Lock()
        self._client_states: list[RtspClientState] = []
        self._running = threading.Event()
        self._running.set()
        self._last_sequence_number: int | None = None
        self._last_rtp_timestamp: int | None = None
        self._last_ssrc: int | None = None
        self._sps_base64: str | None = None
        self._pps_base64: str | None = None
        self._profile_level_id: str | None = None
        self._udp_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._udp_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._udp_socket.bind((listen_host, udp_port))
        self._udp_socket.settimeout(0.5)
        self._rtp_send_socket, self._rtcp_send_socket = self._create_udp_server_pair()
        self._thread = threading.Thread(target=self._relay_loop, daemon=True)

    def _create_udp_server_pair(self) -> tuple[socket.socket, socket.socket]:
        """
        Summary: Create a consecutive RTP/RTCP UDP port pair for RTSP UDP transport.
        Param: none.
        Return: Bound RTP and RTCP UDP sockets.
        """
        for _ in range(50):
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
            return rtp_socket, rtcp_socket
        raise OSError("Unable to allocate consecutive UDP server RTP/RTCP ports")

    def start(self) -> None:
        """
        Summary: Start the relay loop thread.
        Param: none.
        Return: None.
        """
        self._thread.start()

    def stop(self) -> None:
        """
        Summary: Stop the relay loop and close sockets.
        Param: none.
        Return: None.
        """
        self._running.clear()
        try:
            self._udp_socket.close()
        except OSError:
            pass
        for send_socket in (self._rtp_send_socket, self._rtcp_send_socket):
            try:
                send_socket.close()
            except OSError:
                pass
        self._thread.join(timeout=2)

    def udp_server_ports(self) -> tuple[int, int]:
        """
        Summary: Return the UDP RTP/RTCP server ports advertised in SETUP responses.
        Param: none.
        Return: Tuple of RTP and RTCP UDP port numbers.
        """
        return self._rtp_send_socket.getsockname()[1], self._rtcp_send_socket.getsockname()[1]

    def set_client(self, client_state: RtspClientState) -> None:
        """
        Summary: Register or update one active RTSP client.
        Param: client_state Client session state.
        Return: None.
        """
        with self._lock:
            for index, existing_state in enumerate(self._client_states):
                if existing_state.socket == client_state.socket:
                    self._client_states[index] = client_state
                    break
            else:
                self._client_states.append(client_state)

    def clear_client(self, client_socket: socket.socket | None = None) -> None:
        """
        Summary: Remove one RTSP client when it disconnects.
        Param: client_socket Optional socket used to avoid clearing a newer client by mistake.
        Return: None.
        """
        with self._lock:
            if client_socket is None:
                self._client_states.clear()
            else:
                self._client_states = [
                    state for state in self._client_states if state.socket != client_socket
                ]

    def snapshot_rtp_info(self) -> tuple[int, int] | None:
        """
        Summary: Return the latest RTP sequence number and timestamp when available.
        Param: none.
        Return: Tuple of sequence number and RTP timestamp or None.
        """
        with self._lock:
            if self._last_sequence_number is None or self._last_rtp_timestamp is None:
                return None
            return self._last_sequence_number, self._last_rtp_timestamp

    def latest_ssrc_hex(self) -> str | None:
        """
        Summary: Return the latest observed SSRC as an uppercase hex string.
        Param: none.
        Return: SSRC string or None.
        """
        with self._lock:
            if self._last_ssrc is None:
                return None
            return f"{self._last_ssrc:08X}"

    def snapshot_h264_parameter_sets(self) -> tuple[str, str, str] | None:
        """
        Summary: Return the latest H264 profile-level-id, SPS, and PPS values when available.
        Param: none.
        Return: Tuple of profile-level-id, SPS base64, and PPS base64 or None.
        """
        with self._lock:
            if self._profile_level_id is None or self._sps_base64 is None or self._pps_base64 is None:
                return None
            return self._profile_level_id, self._sps_base64, self._pps_base64

    def wait_for_h264_parameter_sets(
        self,
        timeout_seconds: float = 3.0,
    ) -> tuple[str, str, str] | None:
        """
        Summary: Wait until SPS/PPS become available from the RTP stream.
        Param: timeout_seconds Maximum time to wait.
        Return: Tuple of profile-level-id, SPS base64, and PPS base64 or None on timeout.
        """
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            snapshot = self.snapshot_h264_parameter_sets()
            if snapshot is not None:
                return snapshot
            time.sleep(0.02)
        return None

    def _remember_h264_parameter_sets(self, packet: bytes) -> None:
        """
        Summary: Parse RTP H264 payloads and cache SPS/PPS for SDP generation.
        Param: packet Raw RTP packet bytes.
        Return: None.
        """
        for nal_unit in self._extract_h264_nal_units(packet):
            nal_type = nal_unit[0] & 0x1F
            if nal_type == 7 and len(nal_unit) >= 4:
                with self._lock:
                    self._sps_base64 = base64.b64encode(nal_unit).decode("ascii")
                    self._profile_level_id = "".join(f"{byte:02X}" for byte in nal_unit[1:4])
            elif nal_type == 8:
                with self._lock:
                    self._pps_base64 = base64.b64encode(nal_unit).decode("ascii")

    def _extract_h264_nal_units(self, packet: bytes) -> list[bytes]:
        """
        Summary: Extract H264 NAL units from one RTP packet for SPS/PPS discovery.
        Param: packet Raw RTP packet bytes.
        Return: List of extracted NAL units.
        """
        if len(packet) < 12 or (packet[0] >> 6) != 2:
            return []

        csrc_count = packet[0] & 0x0F
        has_extension = (packet[0] & 0x10) != 0
        payload_offset = 12 + 4 * csrc_count
        if len(packet) < payload_offset:
            return []

        if has_extension:
            if len(packet) < payload_offset + 4:
                return []
            extension_length_words = int.from_bytes(
                packet[payload_offset + 2 : payload_offset + 4],
                byteorder="big",
            )
            payload_offset += 4 + extension_length_words * 4
            if len(packet) < payload_offset:
                return []

        payload = packet[payload_offset:]
        if not payload:
            return []

        nal_type = payload[0] & 0x1F
        if 1 <= nal_type <= 23:
            return [payload]

        if nal_type == 24:
            nal_units: list[bytes] = []
            stap_offset = 1
            while stap_offset + 2 <= len(payload):
                nal_size = int.from_bytes(payload[stap_offset : stap_offset + 2], byteorder="big")
                stap_offset += 2
                if nal_size <= 0 or stap_offset + nal_size > len(payload):
                    break
                nal_units.append(payload[stap_offset : stap_offset + nal_size])
                stap_offset += nal_size
            return nal_units

        return []

    def _relay_loop(self) -> None:
        """
        Summary: Continuously receive UDP RTP packets and relay them to RTSP clients.
        Param: none.
        Return: None.
        """
        while self._running.is_set():
            try:
                packet, _ = self._udp_socket.recvfrom(MAX_UDP_PACKET_SIZE)
            except socket.timeout:
                continue
            except OSError:
                break

            if len(packet) >= 12 and (packet[0] >> 6) == 2:
                sequence_number = int.from_bytes(packet[2:4], byteorder="big")
                rtp_timestamp = int.from_bytes(packet[4:8], byteorder="big")
                ssrc = int.from_bytes(packet[8:12], byteorder="big")
                with self._lock:
                    self._last_sequence_number = sequence_number
                    self._last_rtp_timestamp = rtp_timestamp
                    self._last_ssrc = ssrc
                self._remember_h264_parameter_sets(packet)

            with self._lock:
                client_states = list(self._client_states)

            broken_sockets: list[socket.socket] = []
            for client_state in client_states:
                if not client_state.playing:
                    continue

                try:
                    if client_state.transport == "udp" and client_state.udp_rtp_endpoint is not None:
                        self._rtp_send_socket.sendto(packet, client_state.udp_rtp_endpoint)
                    else:
                        frame = (
                            b"$"
                            + bytes([client_state.rtp_channel])
                            + len(packet).to_bytes(2, byteorder="big")
                            + packet
                        )
                        client_state.socket.sendall(frame)
                except OSError:
                    broken_sockets.append(client_state.socket)

            for broken_socket in broken_sockets:
                self.clear_client(broken_socket)


class RtspServer:
    """
    Summary: Provide a minimal RTSP server for one embedded H.264 stream.
    Return: Embedded RTSP server.
    """

    def __init__(
        self,
        listen_host: str,
        port: int,
        path: str,
        relay: RtpTcpRelay,
        sdp_text: str,
    ) -> None:
        """
        Summary: Initialize the RTSP TCP server.
        Param: listen_host Server listen host.
        Param: port Server listen port.
        Param: path RTSP resource path.
        Param: relay Shared RTP relay.
        Param: sdp_text SDP returned by DESCRIBE.
        Return: None.
        """
        self.listen_host = listen_host
        self.port = port
        self.path = path.strip("/")
        self.relay = relay
        self.sdp_text = sdp_text
        self._running = threading.Event()
        self._running.set()
        self._client_lock = threading.Lock()
        self._client_sockets: list[socket.socket] = []
        self._client_threads: list[threading.Thread] = []
        self._server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._server_socket.bind((listen_host, port))
        self._server_socket.listen(8)
        self._server_socket.settimeout(0.5)
        self._thread = threading.Thread(target=self._accept_loop, daemon=True)

    def start(self) -> None:
        """
        Summary: Start the RTSP accept loop thread.
        Param: none.
        Return: None.
        """
        self._thread.start()

    def stop(self) -> None:
        """
        Summary: Stop the RTSP server and close the listening socket.
        Param: none.
        Return: None.
        """
        self._running.clear()
        try:
            self._server_socket.close()
        except OSError:
            pass
        with self._client_lock:
            client_sockets = list(self._client_sockets)
        for client_socket in client_sockets:
            try:
                client_socket.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                client_socket.close()
            except OSError:
                pass
        self._thread.join(timeout=2)
        with self._client_lock:
            client_threads = list(self._client_threads)
        for client_thread in client_threads:
            client_thread.join(timeout=1)

    def _accept_loop(self) -> None:
        """
        Summary: Accept RTSP client connections and serve them concurrently.
        Param: none.
        Return: None.
        """
        while self._running.is_set():
            try:
                client_socket, client_address = self._server_socket.accept()
            except socket.timeout:
                continue
            except OSError:
                break

            with self._client_lock:
                self._client_sockets.append(client_socket)
            client_thread = threading.Thread(
                target=self._serve_client_connection,
                args=(client_socket, client_address),
                daemon=True,
            )
            with self._client_lock:
                self._client_threads.append(client_thread)
            client_thread.start()

    def _serve_client_connection(
        self,
        client_socket: socket.socket,
        client_address: tuple[str, int],
    ) -> None:
        """
        Summary: Own the lifecycle of one connected RTSP client socket.
        Param: client_socket Connected RTSP socket.
        Param: client_address Client address tuple.
        Return: None.
        """
        print(f"[rtsp] Client connected: {client_address[0]}:{client_address[1]}")
        try:
            self._handle_client(client_socket, client_address)
        finally:
            self.relay.clear_client(client_socket)
            try:
                client_socket.close()
            except OSError:
                pass
            with self._client_lock:
                self._client_sockets = [
                    socket_item for socket_item in self._client_sockets if socket_item != client_socket
                ]
                current_thread = threading.current_thread()
                self._client_threads = [
                    thread_item for thread_item in self._client_threads if thread_item is not current_thread
                ]
            print(f"[rtsp] Client disconnected: {client_address[0]}:{client_address[1]}")

    def _handle_client(
        self,
        client_socket: socket.socket,
        client_address: tuple[str, int],
    ) -> None:
        """
        Summary: Process RTSP requests from a single client connection.
        Param: client_socket Connected RTSP socket.
        Param: client_address Client address tuple.
        Return: None.
        """
        client_socket.settimeout(0.5)
        buffer = b""
        session_id = uuid.uuid4().hex[:8]
        client_state = RtspClientState(
            session_id=session_id,
            socket=client_socket,
            address=client_address,
        )

        while self._running.is_set():
            try:
                chunk = client_socket.recv(4096)
            except socket.timeout:
                continue
            except OSError:
                break

            if not chunk:
                break
            buffer += chunk

            while True:
                if buffer.startswith(b"$"):
                    if len(buffer) < 4:
                        break
                    frame_length = int.from_bytes(buffer[2:4], byteorder="big")
                    if len(buffer) < 4 + frame_length:
                        break
                    buffer = buffer[4 + frame_length :]
                    continue

                header_end = buffer.find(b"\r\n\r\n")
                if header_end < 0:
                    break

                request_bytes = buffer[: header_end + 4]
                buffer = buffer[header_end + 4 :]
                should_close = self._process_request(client_socket, client_state, request_bytes)
                if should_close:
                    return

    def _process_request(
        self,
        client_socket: socket.socket,
        client_state: RtspClientState,
        request_bytes: bytes,
    ) -> bool:
        """
        Summary: Parse and respond to a single RTSP request.
        Param: client_socket Connected RTSP socket.
        Param: client_state Mutable RTSP client state.
        Param: request_bytes Raw RTSP request bytes.
        Return: True when the client session should close.
        """
        request_text = request_bytes.decode("iso-8859-1", errors="replace")
        lines = [line for line in request_text.split("\r\n") if line]
        if not lines:
            return False

        try:
            method, uri, _ = lines[0].split(" ", 2)
        except ValueError:
            self._send_response(client_socket, 400, "Bad Request", {"CSeq": "0"})
            return True

        headers: dict[str, str] = {}
        for line in lines[1:]:
            if ":" not in line:
                continue
            key, value = line.split(":", 1)
            headers[key.strip().lower()] = value.strip()

        cseq = headers.get("cseq", "0")
        print(f"[rtsp] {method} {uri} cseq={cseq}")

        if method == "OPTIONS":
            self._send_response(
                client_socket,
                200,
                "OK",
                {
                    "CSeq": cseq,
                    "Public": "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER, SET_PARAMETER",
                },
            )
            return False

        if method == "DESCRIBE":
            describe_headers = {
                "CSeq": cseq,
                "Content-Base": self._base_rtsp_url(uri),
                "Content-Type": "application/sdp",
            }
            self._send_response(
                client_socket,
                200,
                "OK",
                describe_headers,
                self.sdp_text.encode("utf-8"),
            )
            return False

        if method == "SETUP":
            transport = headers.get("transport", "")
            transport_upper = transport.upper()
            if "RTP/AVP/TCP" in transport_upper:
                interleaved_match = re.search(r"interleaved=(\d+)-(\d+)", transport, re.IGNORECASE)
                if interleaved_match:
                    client_state.rtp_channel = int(interleaved_match.group(1))
                    client_state.rtcp_channel = int(interleaved_match.group(2))
                client_state.transport = "tcp"

                self.relay.set_client(client_state)
                response_transport = (
                    f"RTP/AVP/TCP;unicast;interleaved={client_state.rtp_channel}-{client_state.rtcp_channel}"
                )
            elif "RTP/AVP" in transport_upper:
                client_port_match = re.search(r"client_port=(\d+)-(\d+)", transport, re.IGNORECASE)
                if client_port_match is None:
                    self._send_response(client_socket, 461, "Unsupported Transport", {"CSeq": cseq})
                    return False

                client_rtp_port = int(client_port_match.group(1))
                client_rtcp_port = int(client_port_match.group(2))
                client_state.transport = "udp"
                client_state.udp_rtp_endpoint = (client_state.address[0], client_rtp_port)
                client_state.udp_rtcp_endpoint = (client_state.address[0], client_rtcp_port)

                self.relay.set_client(client_state)
                server_rtp_port, server_rtcp_port = self.relay.udp_server_ports()
                protocol = "RTP/AVP/UDP" if "RTP/AVP/UDP" in transport_upper else "RTP/AVP"
                response_transport = (
                    f"{protocol};unicast;client_port={client_rtp_port}-{client_rtcp_port};"
                    f"server_port={server_rtp_port}-{server_rtcp_port}"
                )
            else:
                self._send_response(client_socket, 461, "Unsupported Transport", {"CSeq": cseq})
                return False

            ssrc_hex = self.relay.latest_ssrc_hex()
            if ssrc_hex is not None:
                response_transport += f";ssrc={ssrc_hex}"

            self._send_response(
                client_socket,
                200,
                "OK",
                {
                    "CSeq": cseq,
                    "Session": client_state.session_id,
                    "Transport": response_transport,
                },
            )
            return False

        if method == "PLAY":
            response_headers = {
                "CSeq": cseq,
                "Session": client_state.session_id,
                "Range": "npt=0.000-",
            }
            rtp_info = self.relay.snapshot_rtp_info()
            if rtp_info is not None:
                sequence_number, rtp_timestamp = rtp_info
                response_headers["RTP-Info"] = (
                    f"url={self._track_rtsp_url(uri)};seq={sequence_number};rtptime={rtp_timestamp}"
                )
            self._send_response(client_socket, 200, "OK", response_headers)
            client_state.playing = True
            self.relay.set_client(client_state)
            return False

        if method == "PAUSE":
            client_state.playing = False
            self.relay.set_client(client_state)
            self._send_response(
                client_socket,
                200,
                "OK",
                {
                    "CSeq": cseq,
                    "Session": client_state.session_id,
                },
            )
            return False

        if method in {"GET_PARAMETER", "SET_PARAMETER"}:
            self._send_response(
                client_socket,
                200,
                "OK",
                {
                    "CSeq": cseq,
                    "Session": client_state.session_id,
                },
            )
            return False

        if method == "TEARDOWN":
            client_state.playing = False
            self.relay.clear_client(client_socket)
            self._send_response(
                client_socket,
                200,
                "OK",
                {
                    "CSeq": cseq,
                    "Session": client_state.session_id,
                },
            )
            return True

        self._send_response(client_socket, 405, "Method Not Allowed", {"CSeq": cseq})
        return False

    def _send_response(
        self,
        client_socket: socket.socket,
        status_code: int,
        reason: str,
        headers: dict[str, str],
        body: bytes | None = None,
    ) -> None:
        """
        Summary: Serialize and send one RTSP response.
        Param: client_socket Connected RTSP socket.
        Param: status_code RTSP status code.
        Param: reason RTSP reason phrase.
        Param: headers Response headers.
        Param: body Optional binary response body.
        Return: None.
        """
        header_lines = [f"RTSP/1.0 {status_code} {reason}"]
        response_headers = dict(headers)
        response_headers.setdefault("Server", "rtsp_noise_server.py")
        if body is not None:
            response_headers["Content-Length"] = str(len(body))
        for key, value in response_headers.items():
            header_lines.append(f"{key}: {value}")
        response_bytes = ("\r\n".join(header_lines) + "\r\n\r\n").encode("iso-8859-1")
        if body is not None:
            response_bytes += body
        client_socket.sendall(response_bytes)

    def _base_rtsp_url(self, request_uri: str) -> str:
        """
        Summary: Build the RTSP base URL returned in DESCRIBE.
        Param: request_uri URI used by the client request.
        Return: Base RTSP URL ending with a slash.
        """
        if request_uri.startswith("rtsp://"):
            without_scheme = request_uri[7:]
            slash_index = without_scheme.find("/")
            authority = without_scheme if slash_index < 0 else without_scheme[:slash_index]
            request_path = self.path if slash_index < 0 else without_scheme[slash_index + 1 :]
            if authority:
                return f"rtsp://{authority}/{request_path.strip('/')}/"
        return f"rtsp://{self.listen_host}:{self.port}/{self.path}/"

    def _track_rtsp_url(self, request_uri: str) -> str:
        """
        Summary: Build the RTSP media-control URL for RTP-Info.
        Param: request_uri URI used by the client request.
        Return: Track RTSP URL.
        """
        return self._base_rtsp_url(request_uri) + "trackID=0"


def main() -> int:
    """
    Summary: Launch FFmpeg, start the embedded RTSP server, and keep both alive.
    Param: none.
    Return: Process exit code.
    """
    args = parse_args()
    advertise_host = args.advertise_host or detect_advertise_host()
    listen_path = args.path.lstrip("/")
    client_url = f"rtsp://{advertise_host}:{args.port}/{listen_path}"
    local_preview_url = f"rtsp://127.0.0.1:{args.port}/{listen_path}"
    script_root = Path(__file__).resolve().parent

    try:
        input_file = resolve_input_file(args, script_root)
    except RuntimeError as exc:
        print(f"Fatal error: {exc}", file=sys.stderr)
        return 1

    temp_dir = Path(tempfile.mkdtemp(prefix="rtsp_video_server_"))
    sdp_path = temp_dir / "stream.sdp"
    ffmpeg_command = build_ffmpeg_command(args, sdp_path, input_file)
    source_description = (
        f"file | {input_file}"
        if input_file is not None
        else f"noise | strength={args.noise}"
    )

    print("Local RTSP video server")
    print(f"Client URL : {client_url}")
    print(f"Preview URL: {local_preview_url}")
    print(f"RTSP bind  : {args.listen_host}:{args.port}")
    print(f"RTP bridge : 127.0.0.1:{args.rtp_port}")
    print(f"Source     : {source_description}")
    print(f"Video spec : {args.width}x{args.height} @ {args.fps}fps")
    print(
        "H264 mode  : "
        f"profile={args.profile}, pix_fmt=yuv420p, GOP={args.gop}, "
        f"bitrate={args.video_bitrate}, transport=rtsp-udp-or-tcp"
    )
    print("Use the Client URL in the Android app RTSP URL field.")
    print("Press Ctrl+C to stop.")
    print()
    print("FFmpeg command:")
    print(quote_command(ffmpeg_command))
    print()

    relay = RtpTcpRelay(udp_port=args.rtp_port)
    relay.start()

    ffmpeg_process: subprocess.Popen[str] | None = None
    rtsp_server: RtspServer | None = None
    preview_process: subprocess.Popen[bytes] | None = None
    log_thread: threading.Thread | None = None

    def shutdown() -> None:
        """
        Summary: Stop the RTSP server, FFmpeg, and temporary files.
        Param: none.
        Return: None.
        """
        if rtsp_server is not None:
            rtsp_server.stop()
        relay.stop()
        if preview_process is not None:
            terminate_process(preview_process)
        if ffmpeg_process is not None:
            terminate_process(ffmpeg_process)
        if log_thread is not None:
            log_thread.join(timeout=1)
        try:
            if sdp_path.exists():
                sdp_path.unlink()
            os.rmdir(temp_dir)
        except OSError:
            pass

    try:
        ffmpeg_process = subprocess.Popen(
            ffmpeg_command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
        log_thread = threading.Thread(target=stream_subprocess_logs, args=(ffmpeg_process,), daemon=True)
        log_thread.start()

        raw_sdp = wait_for_sdp_file(sdp_path)
        sdp_includes_parameter_sets = (
            "sprop-parameter-sets=" in raw_sdp and "profile-level-id=" in raw_sdp
        )
        parameter_sets = relay.wait_for_h264_parameter_sets(timeout_seconds=3.0)
        if parameter_sets is None:
            if sdp_includes_parameter_sets:
                print("FFmpeg SDP already includes H264 parameter sets; using SDP values directly.")
            else:
                print(
                    "Warning: neither RTP sniffing nor FFmpeg SDP exposed H264 parameter sets; "
                    "Android clients may reject the stream.",
                )
            adapted_sdp = build_rtsp_sdp(raw_sdp)
        else:
            profile_level_id, sps_base64, pps_base64 = parameter_sets
            print(
                "Detected H264 parameter sets: "
                f"profile-level-id={profile_level_id} "
                f"sprop-parameter-sets={sps_base64},{pps_base64}",
            )
            adapted_sdp = build_rtsp_sdp(
                raw_sdp,
                profile_level_id=profile_level_id,
                sps_base64=sps_base64,
                pps_base64=pps_base64,
            )
        print("Adapted SDP:")
        print(adapted_sdp)

        rtsp_server = RtspServer(
            listen_host=args.listen_host,
            port=args.port,
            path=listen_path,
            relay=relay,
            sdp_text=adapted_sdp,
        )
        rtsp_server.start()

        if args.preview:
            ffplay_command = build_ffplay_command(
                ffplay_bin=args.ffplay_bin,
                preview_url=local_preview_url,
                window_title=args.preview_title,
            )
            print("Launching local preview window:")
            print(quote_command(ffplay_command))
            try:
                preview_process = subprocess.Popen(
                    ffplay_command,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
            except FileNotFoundError:
                print(
                    f"Warning: local preview disabled because ffplay was not found: {args.ffplay_bin}",
                    file=sys.stderr,
                )

        stop_requested = threading.Event()

        def handle_interrupt(signum: int, frame: object) -> None:
            """
            Summary: Stop the server when the user interrupts the script.
            Param: signum Signal number.
            Param: frame Python frame object.
            Return: None.
            """
            del signum, frame
            if not stop_requested.is_set():
                stop_requested.set()
                print("\nStopping RTSP video server...")
                shutdown()

        signal.signal(signal.SIGINT, handle_interrupt)
        if hasattr(signal, "SIGTERM"):
            signal.signal(signal.SIGTERM, handle_interrupt)

        while ffmpeg_process.poll() is None and not stop_requested.is_set():
            time.sleep(0.25)

        if stop_requested.is_set():
            return 0

        print("FFmpeg exited unexpectedly.")
        shutdown()
        return ffmpeg_process.returncode
    except FileNotFoundError:
        relay.stop()
        print(f"Failed to start FFmpeg: executable not found: {args.ffmpeg_bin}", file=sys.stderr)
        return 1
    except Exception as exc:
        print(f"Fatal error: {exc}", file=sys.stderr)
        shutdown()
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
