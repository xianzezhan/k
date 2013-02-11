package org.kframework.parser.generator;

import java.util.HashSet;
import java.util.List;

import org.kframework.kil.Definition;
import org.kframework.kil.Lexical;
import org.kframework.kil.Module;
import org.kframework.kil.Production;
import org.kframework.kil.ProductionItem;
import org.kframework.kil.ProductionItem.ProductionType;
import org.kframework.kil.Restrictions;
import org.kframework.kil.Sort;
import org.kframework.kil.Terminal;
import org.kframework.kil.UserList;
import org.kframework.kil.loader.DefinitionHelper;
import org.kframework.utils.StringUtil;

/**
 * Collect the syntax module, call the syntax collector and print SDF for programs.
 * 
 * @author RaduFmse
 * 
 */
public class ProgramSDF {

	public static String getSdfForPrograms(Definition def) {

		// collect all the syntax modules
		CollectSynModulesVisitor csmv = new CollectSynModulesVisitor();
		def.accept(csmv);

		// collect the syntax from those modules
		ProgramSDFVisitor psdfv = new ProgramSDFVisitor();
		CollectTerminalsVisitor ctv = new CollectTerminalsVisitor();
		for (String modName : csmv.synModNames) {
			Module m = def.getModulesMap().get(modName);
			m.accept(psdfv);
			m.accept(ctv);
		}

		StringBuilder sdf = new StringBuilder();
		sdf.append("module Program\n\n");
		sdf.append("imports Common\n");
		sdf.append("imports KBuiltinsBasic\n");
		sdf.append("exports\n\n");
		sdf.append("context-free syntax\n");
		sdf.append(psdfv.sdf);

		sdf.append("context-free start-symbols\n");
		// sdf.append(StringUtil.escapeSortName(DefinitionHelper.startSymbolPgm) + "\n");
		for (String s : psdfv.startSorts) {
			if (!s.equals("Start"))
				sdf.append(StringUtil.escapeSortName(s) + " ");
		}
		sdf.append("K\n");

		sdf.append("context-free syntax\n");

		for (Production p : psdfv.outsides) {
			if (p.isListDecl()) {
				UserList si = (UserList) p.getItems().get(0);
				sdf.append("	{" + StringUtil.escapeSortName(si.getSort()) + " \"" + si.getSeparator() + "\"}* -> " + StringUtil.escapeSortName(p.getSort()));
				sdf.append(" {cons(\"" + p.getAttribute("cons") + "\")}\n");
			} else {
				sdf.append("	");
				List<ProductionItem> items = p.getItems();
				for (int i = 0; i < items.size(); i++) {
					ProductionItem itm = items.get(i);
					if (itm.getType() == ProductionType.TERMINAL) {
						Terminal t = (Terminal) itm;
						sdf.append("\"" + StringUtil.escape(t.getTerminal()) + "\" ");
					} else if (itm.getType() == ProductionType.SORT) {
						Sort srt = (Sort) itm;
						sdf.append(StringUtil.escapeSortName(srt.getName()) + " ");
					}
				}
				sdf.append("-> " + StringUtil.escapeSortName(p.getSort()));
				sdf.append(SDFHelper.getSDFAttributes(p.getAttributes()) + "\n");
			}
		}

		for (String ss : psdfv.sorts)
			sdf.append("	" + StringUtil.escapeSortName(ss) + " -> InsertDz" + StringUtil.escapeSortName(ss) + "\n");

		sdf.append("\n\n");
		for (String sort : psdfv.constantSorts) {
			String s = StringUtil.escapeSortName(sort);
			sdf.append("	Dz" + s + "		-> " + s + "	{cons(\"" + s + "1Const\")}\n");
		}

		sdf.append("\n");
		sdf.append("	DzDzINT		-> DzDzInt\n");
		sdf.append("	DzDzID		-> DzDzId\n");
		sdf.append("	DzDzSTRING	-> DzDzString\n");
		sdf.append("	DzDzFLOAT	-> DzDzFloat\n");
		sdf.append("\n");

		sdf.append("\n%% start symbols subsorts\n");
		for (String s : psdfv.startSorts) {
			if (!Sort.isBasesort(s) && !DefinitionHelper.isListSort(s))
				sdf.append("	" + StringUtil.escapeSortName(s) + "		-> K\n");
		}

		sdf.append("lexical syntax\n");
		for (Production prd : psdfv.constants) {
			sdf.append("	" + prd.getItems().get(0) + " -> Dz" + StringUtil.escapeSortName(prd.getSort()) + "\n");
		}

		sdf.append("\n\n");

		for (String t : ctv.terminals) {
			if (t.matches("[a-zA-Z][a-zA-Z0-9]*")) {
				sdf.append("	\"" + t + "\" -> DzDzID {reject}\n");
			}
		}

		sdf.append("\n");
		sdf.append(SDFHelper.getFollowRestrictionsForTerminals(ctv.terminals));

		sdf.append("\n\n");

		// lexical rules
		sdf.append("lexical syntax\n");
		java.util.Set<String> lexerSorts = new HashSet<String>();
		for (Production p : psdfv.lexical) {
			Lexical l = (Lexical) p.getItems().get(0);
			lexerSorts.add(p.getSort());
			sdf.append("	" + l.getLexicalRule() + " -> " + StringUtil.escapeSortName(p.getSort()) + "Dz\n");
			if (l.getFollow() != null && !l.getFollow().equals("")) {
				psdfv.restrictions.add(new Restrictions(p.getSort(), null, l.getFollow()));
			}
		}

		// adding cons over lexical rules
		sdf.append("context-free syntax\n");
		for (String s : lexerSorts) {
			sdf.append("	" + StringUtil.escapeSortName(s) + "Dz -> " + StringUtil.escapeSortName(s) + " {cons(\"" + StringUtil.escapeSortName(s) + "1Const\")}\n");
		}
		sdf.append("\n\n");

		// follow restrictions
		sdf.append("lexical restrictions\n");
		for (Restrictions r : psdfv.restrictions) {
			if (r.getTerminal() != null && !r.getTerminal().getTerminal().equals(""))
				sdf.append("	" + r.getTerminal() + " -/- " + r.getPattern() + "\n");
			else
				sdf.append("	" + StringUtil.escapeSortName(r.getSort().getName()) + " -/- " + r.getPattern() + "\n");
		}

		return sdf.toString();
	}
}
