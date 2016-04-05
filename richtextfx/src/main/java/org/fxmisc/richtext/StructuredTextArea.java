package org.fxmisc.richtext;

import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.google.common.collect.TreeRangeMap;
import javafx.beans.DefaultProperty;
import javafx.beans.NamedArg;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;

/**
 * Created by Geoff on 3/29/2016.
 */
@DefaultProperty("highlights")
public class StructuredTextArea extends CodeArea {

    private final Class<? extends Parser>     parserClass;
    private final Class<? extends Lexer>      lexerClass;
    private final Function<Parser, ParseTree> rootProduction;

    private boolean wasSelectingBracketOnLastUpdate = false;

    private final RangeMap<Integer, ParserRuleContext> nonterminalsByCharacterIndex = TreeRangeMap.create();
    private final RangeMap<Integer, Token> tokensByCharacterIndex = TreeRangeMap.create();

    // TODO if i make this type generic on the parser I get a little extra type safety on root production
    // similarly a kotlin data class would do it.

    private final ObservableList<ContextualHighlight> highlights = FXCollections.observableArrayList();

    public StructuredTextArea(@NamedArg("parserClass") String parserClass,
                              @NamedArg("lexerClass") String lexerClass,
                              @NamedArg("rootProduction") String rootProduction) {
        super();

        this.parserClass = loadClass("parserClass", parserClass, Parser.class);
        this.lexerClass = loadClass("lexerClass", lexerClass, Lexer.class);

        try {
            Method rootProductionMethod = this.parserClass.getMethod(rootProduction);
            this.rootProduction = parser -> {
                try {
                    return ParseTree.class.cast(rootProductionMethod.invoke(parser));
                }
                catch (IllegalAccessException | InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            };
        }
        catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }

        caretPositionProperty().addListener((source, oldIndex, newIndex) -> {
            if(wasSelectingBracketOnLastUpdate || isBracket(newIndex)){
                reApplyStyles();
            }
        });

        richChanges().subscribe(change -> reApplyStyles());

        highlights.addListener((ListChangeListener<ContextualHighlight>) c -> {
            while(c.next()){
                c.getAddedSubList().forEach(highlight -> highlight.setAndValidateParentContext(this.parserClass));
            }
        });
    }

    private void reApplyStyles() {

        ANTLRInputStream antlrStringStream = new ANTLRInputStream(getText());

        Lexer lexer = null;
        try {
            lexer = lexerClass.getConstructor(CharStream.class).newInstance(antlrStringStream);
        }
        catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }

        lexer.removeErrorListeners();
        TokenStream tokens = new CommonTokenStream(lexer);
        Parser parser = null;

        try {
            parser = parserClass.getConstructor(TokenStream.class).newInstance(tokens);
        }
        catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }

        parser.getErrorListeners().removeIf(ConsoleErrorListener.class::isInstance);

        ParseTree expr = rootProduction.apply(parser);

        List<HighlightedTextInteveral> foundHighlights = new ArrayList<>();

        tokensByCharacterIndex.clear();
        nonterminalsByCharacterIndex.clear();

        ParseTreeWalker walker = new ParseTreeWalker();
        ParseTreeListener walkListener = new ParseTreeListener() {
            @Override public void visitTerminal(TerminalNode terminalNode) {
                Token symbol = terminalNode.getSymbol();
                if(symbol.getType() != Token.EOF) {
                    Range<Integer> range = Range.closed(symbol.getStartIndex(), symbol.getStopIndex());
                    tokensByCharacterIndex.put(range, symbol);
                }
            }
            @Override public void visitErrorNode(ErrorNode errorNode) {
                int x = 4;
            }
            @Override public void enterEveryRule(ParserRuleContext ctx) {

                int startIndex = ctx.getStart().getStartIndex();
                int stopIndex = ctx.getStop().getStopIndex();

                // remember we're editing live code,
                // ANTLRs behaviour when you specify a rule that it cant complete is to give you would-be
                // tokens as start and stop
                // we can check for this easily enough
                if (startIndex < stopIndex) {
                    Range<Integer> nonterminalRange = Range.closed(startIndex, stopIndex);
                    nonterminalsByCharacterIndex.put(nonterminalRange, ctx);
                }
            }
            @Override public void exitEveryRule(ParserRuleContext ctx) {

                Optional<HighlightedTextInteveral> targetHighlight = getHighlights().stream()
                        .map(highlight -> highlight.getMatchingText(ctx))
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .findFirst();

                targetHighlight.ifPresent(foundHighlights::add);
            }
        };
        walker.walk(walkListener, expr);

        foundHighlights.addAll(generateBracketHighlights());
        foundHighlights.sort((l, r) -> l.getLowerBound() - r.getLowerBound());

        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        int lastHighlightEnd = 0;
        //TODO overlapping highlights?

        for(HighlightedTextInteveral interval : foundHighlights){
            spansBuilder.add(Collections.emptyList(), interval.getLowerBound() - lastHighlightEnd);
            spansBuilder.add(Collections.singleton(interval.getStyleClass()), (interval.getUpperBound() + 1) - interval.getLowerBound());
            lastHighlightEnd = interval.getUpperBound() + 1;
        }

        spansBuilder.add(Collections.emptyList(), getLength() - lastHighlightEnd);

        setStyleSpans(0, spansBuilder.create());
    }

    private Collection<HighlightedTextInteveral> generateBracketHighlights() {
        int selectedIndex = getCaretPosition();

        if( ! isBracket(selectedIndex)){
            wasSelectingBracketOnLastUpdate = false;
            return Collections.emptyList();
        }


        Token selectedToken = tokensByCharacterIndex.get(selectedIndex);
        ParserRuleContext selectedNode = nonterminalsByCharacterIndex.get(selectedIndex);
        if(selectedNode == null || selectedToken == null) {
            wasSelectingBracketOnLastUpdate = false;
            return Collections.emptyList();
        }

        String selectedText = selectedToken.getText();
        String mirrorText = selectedText.equals(")") ? "(" : ")";

        Optional<TerminalNode> possibleTwinNode = selectedNode.children.stream()
                .filter(TerminalNode.class::isInstance).map(TerminalNode.class::cast)
                .filter(node -> node.getSymbol().getText().equals(mirrorText))
                .findFirst();

        wasSelectingBracketOnLastUpdate = possibleTwinNode.isPresent();

        return possibleTwinNode.map(TerminalNode::getSymbol).map(mirror -> Arrays.asList(
                new HighlightedTextInteveral(selectedToken.getStartIndex(), selectedToken.getStopIndex(), "bracket"),
                new HighlightedTextInteveral(mirror.getStartIndex(), mirror.getStopIndex(), "bracket")
        )).orElse(Collections.emptyList());
    }

    private boolean isBracket(int index) {
        Token selectedToken = tokensByCharacterIndex.get(index);

        if(selectedToken == null){
            return false;
        }

        return "(".equals(selectedToken.getText()) || ")".equals(selectedToken.getText());
    }

    public final ObservableList<ContextualHighlight> getHighlights(){
        return highlights;
    }

    public static <TClass> Class<? extends TClass> loadClass(String varName, String className, Class<TClass> neededSuperClass){
        try{
            Class candidate = Class.forName(className);
            if ( ! neededSuperClass.isAssignableFrom(candidate)){
                throw new IllegalArgumentException(varName + "; " + className + " must be subclass of " + neededSuperClass.getCanonicalName());
            }
            return (Class) candidate;
        }
        catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(className + " not found for " + varName, e);
        }
    }
}
