package com.databridge.core.setup;

/** The setups in force: shipped defaults merged with installation overrides. */
public record EffectiveSetup(DataBridgeSetup general, TemplateSetup templates) { }
