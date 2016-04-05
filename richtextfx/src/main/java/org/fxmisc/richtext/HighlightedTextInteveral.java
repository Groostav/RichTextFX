package org.fxmisc.richtext;

/**
 * Created by Geoff on 4/4/2016.
 */
public class HighlightedTextInteveral {

    private final int lowerBound;
    private final int upperBound;
    private final String styleClass;

    /**
     * a interval of text with a given style
     *
     * @param lowerBound the index of the first character to be applied with this style (inclusive)
     * @param upperBound the index of the last character to be applied with this style (exclusive)
     * @param styleClass the name of the style class to apply to the interval of text
     */
    public HighlightedTextInteveral(int lowerBound, int upperBound, String styleClass) {
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
        this.styleClass = styleClass;
    }

    public int getLowerBound() {
        return lowerBound;
    }

    public int getUpperBound() {
        return upperBound;
    }

    public String getStyleClass() {
        return styleClass;
    }
}
