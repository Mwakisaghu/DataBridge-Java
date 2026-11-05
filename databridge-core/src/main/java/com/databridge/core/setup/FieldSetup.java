package com.databridge.core.setup;

import java.util.ArrayList;
import java.util.List;

/**
 * One column in a Template Setup. {@code type} uses the same words as the
 * template header comments: Code20, Text100, Decimal, Integer, Boolean, Date,
 * DateFormula or Option.
 */
public class FieldSetup {

    private String caption;
    private String type;
    /** Option captions in ordinal order, when type is Option. */
    private List<String> options = new ArrayList<>();
    private boolean required;

    public String getCaption() { return caption; }
    public String getType() { return type; }
    public List<String> getOptions() { return options; }
    public boolean isRequired() { return required; }

    public void setCaption(String caption) { this.caption = caption; }
    public void setType(String type) { this.type = type; }
    public void setOptions(List<String> options) { this.options = options == null ? new ArrayList<>() : options; }
    public void setRequired(boolean required) { this.required = required; }
}
