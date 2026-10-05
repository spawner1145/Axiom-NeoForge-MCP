package dev.axiommcp.ui;

import dev.axiommcp.AxiomMcp;
import imgui.moulberry92.ImGui;
import imgui.moulberry92.type.ImBoolean;
import imgui.moulberry92.type.ImInt;
import imgui.moulberry92.type.ImString;
import net.minecraft.client.Minecraft;

public final class McpMenu {
    private static final ImBoolean open = new ImBoolean(false);
    private static final ImString host = new ImString(256);
    private static final ImInt port = new ImInt(8766);
    private static String error = "";
    private static long renderedFrames;
    public static long renderedFrames() { return renderedFrames; }
    public static boolean isOpen() { return open.get(); }
    public static void show() {
        host.set(AxiomMcp.get().config().host()); port.set(AxiomMcp.get().config().port()); error = ""; open.set(true);
    }
    private static String text(String en, String zh) {
        return Minecraft.getInstance().options.languageCode.startsWith("zh") ? zh : en;
    }
    public static void menu() {
        if (AxiomMcp.get() == null) return;
        if (ImGui.beginMenu("MCP")) {
            if (ImGui.menuItem(text("Connection / Settings", "连接 / 设置"))) show();
            if (ImGui.menuItem(text("Copy endpoint", "复制连接地址"))) copy();
            if (ImGui.menuItem(AxiomMcp.get().server.isRunning() ? text("Stop service", "停止服务") : text("Start service", "启动服务"))) toggle();
            ImGui.endMenu();
        }
    }
    public static void panel() {
        if (!open.get() || AxiomMcp.get() == null) return;
        renderedFrames++;
        ImGui.setNextWindowSize(560, 420, 4);
        if (ImGui.begin(text("Axiom MCP Settings###AxiomMCP", "Axiom MCP 设置###AxiomMCP"), open)) {
            var mod = AxiomMcp.get(); var status = mod.server.status();
            ImGui.text(text("Status: ", "状态：") + (mod.server.isRunning() ? text("Running", "运行中") : text("Stopped", "已停止")));
            ImGui.textWrapped(mod.config().endpoint());
            ImGui.inputText("Host", host); ImGui.inputInt("Port", port);
            if (ImGui.button(text("Apply and restart", "保存并重启"))) {
                try { mod.configure(host.get(), port.get()); error = ""; } catch (Exception exception) { error = exception.toString(); }
            }
            ImGui.sameLine(); if (ImGui.button(text("Copy endpoint", "复制地址"))) copy();
            if (ImGui.button(mod.server.isRunning() ? text("Stop", "停止") : text("Start", "启动"))) toggle();
            ImGui.separator();
            ImGui.textWrapped(text("Recent clients: ", "最近客户端：") + status.get("recentClients"));
            ImGui.text(text("Requests: ", "请求数：") + status.get("requests").getAsLong());
            ImGui.textWrapped(text("Last tool: ", "最近工具：") + status.get("lastTool").getAsString());
            ImGui.textWrapped(text("Last request: ", "最近请求：") + status.get("lastRequest").getAsString());
            var failure = !error.isEmpty() ? error : !mod.startupError().isEmpty() ? mod.startupError() : status.get("lastError").getAsString();
            if (!failure.isEmpty()) ImGui.textWrapped(failure);
        }
        ImGui.end();
    }
    private static void copy() { Minecraft.getInstance().keyboardHandler.setClipboard(AxiomMcp.get().config().endpoint()); }
    private static void toggle() {
        try { if (AxiomMcp.get().server.isRunning()) AxiomMcp.get().server.close(); else AxiomMcp.get().start(); error = ""; }
        catch (Exception exception) { error = exception.toString(); }
    }
}
