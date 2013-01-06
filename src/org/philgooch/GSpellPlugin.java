/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.philgooch;

import gate.*;
import gate.creole.*;
import gate.creole.metadata.*;
import gate.util.*;

import gate.Annotation;
import gate.AnnotationSet;
import gate.Document;
import gate.Factory;
import gate.FeatureMap;

import java.util.*;
import java.io.*;
import java.net.URL;

import gov.nih.nlm.nls.gspell.*;
import gov.nih.nlm.nls.gspell.GSpellException;

/**
 *
 * @author philipgooch
 */
@CreoleResource(name = "GSpell Spelling Suggester",
helpURL = "",
comment = "Plugin that wraps the GSpell API to add spelling suggestions to features in the input/output annotations defined.")
public class GSpellPlugin extends AbstractLanguageAnalyser implements
        ProcessingResource,
        Serializable {

    private GSpell gspell;      // Single instance of GSpell per PR Instance
    // fields for holding config and class properties
    private String inputASName;     //  Input AnnotationSet name
    private String outputASName;    // Output AnnotationSet set name
    private ArrayList<String> inputASTypes; // list of input annotations from which string content will be spell-checked
    private String outputASType;    // Output annotation name within outputASName for spell-checked annotations
    private String inputASTypeFeature;      // Name of feature within inputASTypes from which string content will be submitted for spell checking
    private String spellSuggestFeature;      // Name of feature within input/outputASType to which spell-corrected string will be added
    private String candidatesFeature;        // Name of feature within input/outputASType that will hold other spelling candidates
    private String dumpOutputFeature;      // Name of feature within input/outputASType to which complete GSpell output should be dumped
    private URL configURL;      // URL to configuration file
    private URL dictionaryURL;      // URL to compiled dictionary file
    private OutputFormat outputListFormat;      // Output Lists as strings or as a List object
    // find options
    private Integer maxCandidates;          // run-time options from .cfg file
    private Boolean useWordLengthHeuristic;
    private Integer truncateSize;
    private Integer maxEditDistance;
    // filter options
    private Integer shortestWord;       // ignore words under this threshold
    private ArrayList<String> filters;  // regex filters for ignoring certain string patterns, e.g. HCO3
    private String tokASName;     //  Name of AnnotationSet containing Tokens
    private String tokType;     //  Name of Token annotation
    private ArrayList<String> excludeIfContains;    // don't spellcheck if term contains one of these annots
    private ArrayList<String> excludeIfWithin;      // don't spellcheck if term occurs inside one of these annots
    // Exit gracefully if exception caught on init()
    private boolean gracefulExit = false;
    // execution options
    private FindOptions findOptions;        // run-time find options
    private AnnotationSet outputAS;         // output AnnotationSet
    private boolean dumpGS;                 // dump GSpell output to feature
    private boolean createNewAnnot;         // create a new annot to hold spell-corrected string
    private Mode mode;              // Spell check whole phrase or individual tokens within the phrase

    // Spell correct whole phrase or individual tokens
    public enum Mode {

        WholePhrase, PhraseTokens
    }

    // Output Lists as strings or as a List object
    public enum OutputFormat {

        String, List
    }

    @Override
    public Resource init() throws ResourceInstantiationException {
        // Default input to GSpell is by Token
        inputASTypes = new ArrayList<String>();
        inputASTypes.add("Token");

        // Set up default filters
        filters = new ArrayList<String>();
        filters.add("[A-Z/\\.\\-]+");            // filter words in allCaps or allCaps + punctuation
        filters.add("^(\\d+.+?)|(.+?\\d+)$");    // filter words starting or ending with a digit

        if (configURL == null || dictionaryURL == null) {
            gracefulExit = true;
            gate.util.Err.println("No configuration or dictionary directory provided!");
        }

        try {
            gspell = new GSpell(configURL.getPath(), dictionaryURL.getPath(), GSpell.READ_ONLY);
        } catch (GSpellException g) {
            gracefulExit = true;
            gate.util.Err.println(g.getMessage());
        }

        return this;
    } // end init()

    @Override
    public void execute() throws ExecutionException {
        // quit if setup failed
        if (gracefulExit) {
            cleanup();
            fireProcessFinished();
            return;
        }

        findOptions = new FindOptions(maxCandidates.intValue(), useWordLengthHeuristic.booleanValue(), truncateSize.intValue(), maxEditDistance.intValue());

        AnnotationSet inputAS = (inputASName == null || inputASName.trim().length() == 0) ? document.getAnnotations() : document.getAnnotations(inputASName);
        outputAS = (outputASName == null || outputASName.trim().length() == 0) ? document.getAnnotations() : document.getAnnotations(outputASName);

        // Get the set of Token annotations, as defined by the user
        String tokName;
        String tokFeat;
        String[] tokArr = tokType.split("\\.");
        if (tokArr.length == 2) {
            tokName = tokArr[0];
            tokFeat = tokArr[1];
        } else {
            tokName = "Token";
            tokFeat = "string";
        }

        AnnotationSet tokenAS = (tokASName == null || tokASName.trim().length() == 0) ? document.getAnnotations().get(tokName) : document.getAnnotations(tokASName).get(tokName);

        String docContent = document.getContent().toString();

        dumpGS = (dumpOutputFeature == null || dumpOutputFeature.isEmpty()) ? false : true;
        createNewAnnot = (outputASType == null || outputASType.isEmpty()) ? false : true;


        // process the content of each annot in inputASTypes
        for (String inputAnnType : inputASTypes) {
            AnnotationSet inputAnnSet;

            // We allow inputAnnType of the form
            // Annotation.feature == value
            String annName = inputAnnType;  // assume just a simple ann name to start with
            String annFeature;
            String annFeatureValue;
            String[] inputAnnArr = inputAnnType.split("(\\.)|(==)");
            if (inputAnnArr.length == 3 || inputAnnArr.length == 2) {
                annName = inputAnnArr[0];
                annFeature = inputAnnArr[1];
                if (inputAnnArr.length == 2) {
                    Set<String> feats = new HashSet<String>();
                    feats.add(annFeature);
                    inputAnnSet = inputAS.get(annName, feats);
                } else {
                    FeatureMap annFeats = Factory.newFeatureMap();
                    annFeatureValue = inputAnnArr[2];
                    annFeats.put(annFeature, annFeatureValue);
                    inputAnnSet = inputAS.get(annName, annFeats);
                }

            } else {
                inputAnnSet = inputAS.get(inputAnnType);
            }
            

            for (Annotation ann : inputAnnSet) {
                boolean skip = false;
                Long annStart = ann.getStartNode().getOffset();
                Long annEnd = ann.getEndNode().getOffset();

                // Don't spellcheck this term if it occurs within or wraps any of these annots
                if (excludeIfWithin != null && !(excludeIfWithin.isEmpty())) {
                    for (String excludeAnnName : excludeIfWithin) {
                        if (! inputAS.getCovering(excludeAnnName, annStart, annEnd).isEmpty()) {
                            skip = true;
                            break;
                        }
                    }
                }
                if (excludeIfContains != null && !(excludeIfContains.isEmpty())) {
                    for (String excludeAnnName : excludeIfContains) {
                        if ( ! inputAS.getContained(annStart, annEnd).get(excludeAnnName).isEmpty() ) {
                            skip = true;
                            break;
                        }
                    }
                }
                if (skip) { continue ; }

                String strTerm = "";            // Term that we want to spellcheck

                Object o = ann.getFeatures().get(inputASTypeFeature);
                String annFeatureContent = (o == null) ? "" : o.toString();

                // Use the content of a named feature as input ?
                if (annFeatureContent == null || annFeatureContent.trim().length() == 0) {
                    strTerm = docContent.substring(annStart.intValue(), annEnd.intValue()).trim();
                } else {
                    strTerm = annFeatureContent.trim();
                }

                // If whole-phrase mode has been selected, or input annotation is Token, then spell-check the phrase text as a unit
                if (mode == Mode.WholePhrase || inputAnnType.equals(tokName)) {
                    spellCheck(strTerm, ann);
                }

                // If phrase-tokens mode has been selected, and if our input annot isn't already a Token, spell-check each Token in the input annot
                if (mode == Mode.PhraseTokens && !inputAnnType.equals(tokName)) {
                    FeatureMap phraseFM;
                    if (createNewAnnot) {
                        phraseFM = Factory.newFeatureMap();
                    } else {
                        phraseFM = ann.getFeatures();
                    }

                    List<Annotation> innerToks = new ArrayList<Annotation>(tokenAS.getContained(annStart, annEnd));
                    Collections.sort(innerToks, new OffsetComparator());
                    // Now spell check each of the Tokens within the annot
                    StringBuilder strbuf = new StringBuilder("");
                    String suggestString;
                    boolean hasSpellSuggestions = false;
                    for (Annotation tok : innerToks) {
                        Object oStr = tok.getFeatures().get(tokFeat);
                        strTerm = (oStr == null) ? "" : oStr.toString();

                        suggestString = spellCheck(strTerm, tok);
                        if (!suggestString.equals(strTerm)) {
                            hasSpellSuggestions = true;
                        }
                        strbuf.append(suggestString);
                        strbuf.append(" ");
                    }
                    if (hasSpellSuggestions) {
                        if (createNewAnnot && innerToks.size() > 1) {
                            outputAS.add(ann.getStartNode(), ann.getEndNode(), outputASType, phraseFM);
                        }
                        phraseFM.put(spellSuggestFeature, strbuf.toString().trim());
                    }

                } // end if

            } // end for

        } // end for

        fireProcessFinished();
    } // end execute()

    /**
     *
     * @param strTerm   text to be spell-checked
     * @param ann       annotation that wraps the text to be spell-checked
     * @return          either the original string or the corrected string
     */
    private String spellCheck(String strTerm, Annotation ann) {
        FeatureMap fm;
        Candidate candidates[] = spellCheck(strTerm);
        String suggestString = strTerm;

        if (createNewAnnot) {
            fm = Factory.newFeatureMap();
        } else {
            fm = ann.getFeatures();
        }

        if (candidates != null && candidates.length > 0) {
            suggestString = candidates[0].getName(); // get the most likely correction as the primary suggestion

            // Only use spelling suggestion if it is equal to or longer than our shortestWord threshold
            if (suggestString.length() >= shortestWord) {
                // create a couple of arrays to hold the spelling candidates and the raw GSpell output
                List<String> gs = new ArrayList<String>();
                List<String> cs = new ArrayList<String>();
                for (Candidate c : candidates) {
                    gs.add(c.toString());
                    cs.add(c.getName());
                }
                if (createNewAnnot) {
                    outputAS.add(ann.getStartNode(), ann.getEndNode(), outputASType, fm);
                }
                fm.put(spellSuggestFeature, suggestString);
                if (outputListFormat == OutputFormat.List) {
                    fm.put(candidatesFeature, cs);
                    if (dumpGS) {
                        fm.put(dumpOutputFeature, gs);
                    }
                } else {
                    fm.put(candidatesFeature, cs.toString());
                    if (dumpGS) {
                        fm.put(dumpOutputFeature, gs.toString());
                    }
                }
            }

        }
        candidates = null;
        return suggestString;
    }

    /**
     *
     * @param strTerm       string to be spell-checked
     * @return              Candidate[] array containing spelling suggestions
     */
    private Candidate[] spellCheck(String strTerm) {
        Candidate candidates[] = null;

        // Check if term matches a filter
        for (String filter : filters) {
            if (strTerm.matches(filter)) {
                return null;
            }
        }

        // Don't bother trying to spellcheck short or filtered words
        // as these tend to trigger false suggestions
        if (strTerm.length() < shortestWord) {
            return null;
        }

        try {
            if (!gspell.exists(strTerm)) {
                candidates = gspell.find(findOptions, strTerm);
            } // end if
        } catch (GSpellException g) {
            // just output error and continue
            gate.util.Err.println(g.getMessage());
        } finally {
            gspell.freeCandidates();
            return candidates;
        } // end try
    }

    @Optional
    @RunTime
    @CreoleParameter(comment = "If set, only send the content of the given feature from annotations within inputASTypes to GSpell")
    public void setInputASTypeFeature(String inputASTypeFeature) {
        this.inputASTypeFeature = inputASTypeFeature;
    }

    public String getInputASTypeFeature() {
        return inputASTypeFeature;
    }

    @RunTime
    @CreoleParameter(defaultValue = "suggest",
    comment = "Name of feature to hold the spell-corrected string")
    public void setSpellSuggestFeature(String spellSuggestFeature) {
        this.spellSuggestFeature = spellSuggestFeature;
    }

    public String getSpellSuggestFeature() {
        return spellSuggestFeature;
    }

    @RunTime
    @CreoleParameter(defaultValue = "candidates",
    comment = "Name of feature to hold the spelling candidates")
    public void setCandidatesFeature(String candidatesFeature) {
        this.candidatesFeature = candidatesFeature;
    }

    public String getCandidatesFeature() {
        return candidatesFeature;
    }

    @Optional
    @RunTime
    @CreoleParameter(comment = "Name of feature to hold the complete GSpell output - leave blank to suppress")
    public void setDumpOutputFeature(String dumpOutputFeature) {
        this.dumpOutputFeature = dumpOutputFeature;
    }

    public String getDumpOutputFeature() {
        return dumpOutputFeature;
    }

    @Optional
    @RunTime
    @CreoleParameter(comment = "Input Annotation Set Name")
    public void setInputASName(String inputASName) {
        this.inputASName = inputASName;
    }

    public String getInputASName() {
        return inputASName;
    }

    @Optional
    @RunTime
    @CreoleParameter(comment = "Output Annotation Set Name")
    public void setOutputASName(String outputASName) {
        this.outputASName = outputASName;
    }

    public String getOutputASName() {
        return outputASName;
    }

    @CreoleParameter(defaultValue = "resources/config",
    comment = "Location of configuration directory")
    public void setConfigURL(URL configFileURL) {
        this.configURL = configFileURL;
    }

    public URL getConfigURL() {
        return configURL;
    }

    @CreoleParameter(defaultValue = "resources/dictionaries/2006Lexicon",
    comment = "Location of compiled dictionary directory")
    public void setDictionaryURL(URL dictionaryURL) {
        this.dictionaryURL = dictionaryURL;
    }

    public URL getDictionaryURL() {
        return dictionaryURL;
    }

    @RunTime
    @CreoleParameter(defaultValue = "2000",
    comment = "Limit the total number of candidates to be evaluated to N")
    public void setMaxCandidates(Integer maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    public Integer getMaxCandidates() {
        return maxCandidates;
    }

    @RunTime
    @CreoleParameter(defaultValue = "2",
    comment = "Limit the returned suggestions to those who have edit distances that are less than or equal to N")
    public void setMaxEditDistance(Integer maxEditDistance) {
        this.maxEditDistance = maxEditDistance;
    }

    public Integer getMaxEditDistance() {
        return maxEditDistance;
    }

    @RunTime
    @CreoleParameter(defaultValue = "1",
    comment = "Return the top N suggestions")
    public void setTruncateSize(Integer truncateSize) {
        this.truncateSize = truncateSize;
    }

    public Integer getTruncateSize() {
        return truncateSize;
    }

    @RunTime
    @CreoleParameter(defaultValue = "true",
    comment = "Limit the candidates to be evaluated to those which are with +/-4 characters of the input term")
    public void setUseWordLengthHeuristic(Boolean useWordLengthHeuristic) {
        this.useWordLengthHeuristic = useWordLengthHeuristic;
    }

    public Boolean getUseWordLengthHeuristic() {
        return useWordLengthHeuristic;
    }

    @Optional
    @RunTime
    @CreoleParameter(comment = "If set, only send the content of the given Annotations in the input Annotation Set to GSpell")
    public void setInputASTypes(ArrayList<String> inputASTypes) {
        this.inputASTypes = inputASTypes;
    }

    public ArrayList<String> getInputASTypes() {
        return inputASTypes;
    }

    @Optional
    @RunTime
    @CreoleParameter(comment = "Write out spell-corrected text to a new annotation")
    public void setOutputASType(String outputASType) {
        this.outputASType = outputASType;
    }

    public String getOutputASType() {
        return outputASType;
    }

    @RunTime
    @CreoleParameter(defaultValue = "4",
    comment = "Minimum word length to trigger spell check")
    public void setShortestWord(Integer shortestWord) {
        this.shortestWord = shortestWord;
    }

    public Integer getShortestWord() {
        return shortestWord;
    }

    @Optional
    @RunTime
    @CreoleParameter(comment = "Regexps that filter phrases to be ignored by spellchecker")
    public void setFilters(ArrayList<String> filters) {
        this.filters = filters;
    }

    public ArrayList<String> getFilters() {
        return filters;
    }

    @RunTime
    @CreoleParameter(defaultValue = "PhraseTokens",
    comment = "Spell-check each phrase, or each token within each phrase")
    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Mode getMode() {
        return mode;
    }

    @Optional
    @RunTime
    @CreoleParameter(comment = "Name of AnnotationSet containing Tokens")
    public void setTokASName(String tokASName) {
        this.tokASName = tokASName;
    }

    public String getTokASName() {
        return tokASName;
    }

    @Optional
    @RunTime
    @CreoleParameter(defaultValue = "Token.string",
    comment = "Name and feature value of Token content")
    public void setTokType(String tokType) {
        this.tokType = tokType;
    }

    public String getTokType() {
        return tokType;
    }

    @RunTime
    @CreoleParameter(defaultValue = "String",
    comment = "Output lists as a string or as a List object")
    public void setOutputListFormat(OutputFormat outputListFormat) {
        this.outputListFormat = outputListFormat;
    }

    public OutputFormat getOutputListFormat() {
        return outputListFormat;
    }

    @Optional
    @RunTime
    @CreoleParameter(comment = "Don't spellcheck terms that contain these annotations")
    public void setExcludeIfContains(ArrayList<String> excludeIfContains) {
        this.excludeIfContains = excludeIfContains;
    }

    public ArrayList<String> getExcludeIfContains() {
        return excludeIfContains;
    }

    @Optional
    @RunTime
    @CreoleParameter(comment = "Don't spellcheck terms that are within these annotations")
    public void setExcludeIfWithin(ArrayList<String> excludeIfWithin) {
        this.excludeIfWithin = excludeIfWithin;
    }

    public ArrayList<String> getExcludeIfWithin() {
        return excludeIfWithin;
    }
}
