# Proguard rules for OpenClaw PoC
# Keep NodeRunner (used reflectively from process callbacks)
-keep class ai.openclaw.poc.NodeRunner { *; }
