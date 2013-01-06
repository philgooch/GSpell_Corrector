GSpell Spelling Suggester 
=================================

This plugin wraps the GSpell API (http://lexsrv3.nlm.nih.gov/LexSysGroup/Projects/gSpell/current/GSpell.html) to add spelling suggestions to features in the input/output annotations defined.

The 2006Lexicon dictionary from the NLM's SPECIALIST toolset is provided by default. See the GSpell project page for information on how to create your own dictionaries, and further configuration options.


Parameters
==========

- Init-time
-----------
configURL: path to the directory containing the GSpellRegistry.cfg file

dictionaryURL: path to the directory containing compiled dictionary data


- Run-time
----------------
candidatesFeature: Create new feature with this name on inputASTypes (or outputASType in outputASName, if set) to hold all spelling candidates returned by GSpell.

dumpOutputFeature: Name of the feature to use to hold the raw GSpell output. Optional

excludeIfContains: If an entry within inputASTypes contain any of the annotations in this list, 
then do not spellcheck this entry.

excludeIfWithin: List of input annotations within which spellchecking should not occur.

filters: List of regular expressions to use to filter the input to the spell checker. Two filters are provided by default: ignore capitalised abbreviations and words in all caps, and words starting or ending with a digit. Optional

inputASName: Input AnnotationSet name. Optional, leave blank for default annotation set.

inputASTypeFeature: Name of the feature on inputASTypes from which to extract strings for input to the spell-checker. Optional, leave blank to use the string content of inputASTypes.

inputASTypes: List of input annotations from which to extract strings for input to the spell-checker. Default is Token. 
This parameter also accepts entries in the form Annotation.feature == value so that you can filter your input annotations
according to feature value (although regexes for value are not currently allowed).

maxCandidates: Maximum number of dictionary candidates to consider.

maxEditDistance: Limit candidates to those with edit distance <= N. Default is 2.

mode: WholePhrase: Spell check the whole string as a single phrase (e.g. 'blood morphagenic protein'). PhraseTokens: spell check individual tokens within the string.

outputASName: Output AnnotationSet name. Optional. Only used if outputASType is set.

outputASType: Create new annotations with this name, to hold the spell-corrected text. Optional - if not specified,
the spellSuggestFeature is added to entries in inputASTypes.

outputListFormat: Set to 'String' so that GSpell ArrayList<String> output can be matched with JAPE LHS expressions. Set to 'List' so that GSpell output can be iterated over with JAPE RHS expressions.

shortestWord: Ignore words shorter than N, and ignore spelling suggestions shorter than N. Default is 4.

spellSuggestFeature: Create new feature with this name on entries within inputASTypes (or outputASType in outputASName, if set) to hold the spell-corrected text.

tokASName: AnnotationSet containing Tokens. Used if mode is set to PhraseTokens. Leave blank for default annotation set.

tokType: Token annotation name and feature that contains the Token string. Default is Token.string

truncateSize: Only return the top N candidates. Default is 4.

useWordLengthHeuristic. Prune potential dictionary candidates that are +/- 4 characters in length larger or smaller than the query term. Default is true.