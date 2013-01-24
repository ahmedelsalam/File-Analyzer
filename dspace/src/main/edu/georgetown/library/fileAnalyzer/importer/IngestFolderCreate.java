package edu.georgetown.library.fileAnalyzer.importer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.TreeMap;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.transform.TransformerException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import edu.georgetown.library.fileAnalyzer.filetest.IngestInventory.InventoryStatsItems;

import gov.nara.nwts.ftapp.ActionResult;
import gov.nara.nwts.ftapp.FTDriver;
import gov.nara.nwts.ftapp.Timer;
import gov.nara.nwts.ftapp.ftprop.FTPropString;
import gov.nara.nwts.ftapp.importer.DefaultImporter;
import gov.nara.nwts.ftapp.importer.DelimitedFileImporter;
import gov.nara.nwts.ftapp.stats.Stats;
import gov.nara.nwts.ftapp.stats.StatsGenerator;
import gov.nara.nwts.ftapp.stats.StatsItem;
import gov.nara.nwts.ftapp.stats.StatsItemConfig;
import gov.nara.nwts.ftapp.stats.StatsItemEnum;
import gov.nara.nwts.ftapp.util.FileUtil;
import gov.nara.nwts.ftapp.util.XMLUtil;

/**
 * @author TBrady
 *
 */
public class IngestFolderCreate extends DefaultImporter {
	private static enum IngestStatsItems implements StatsItemEnum {
		LineNo(StatsItem.makeStringStatsItem("Line No").setExport(false).setWidth(60)),
		Folder(StatsItem.makeStringStatsItem("Folder")),
		Status(StatsItem.makeEnumStatsItem(status.class, "Status").setWidth(60)),
		InputMetadata(StatsItem.makeEnumStatsItem(InputMetaStats.class, "Input Metadata")),
		ItemFileStats(StatsItem.makeEnumStatsItem(FileStats.class,"Item File Status").setWidth(120)),
		ThumbFileStats(StatsItem.makeEnumStatsItem(FileStats.class, "Thumb File Status").setWidth(120)),
		LicenseFileStats(StatsItem.makeEnumStatsItem(FileStats.class, "License File Status").setWidth(120)),
		ContentsFileStats(StatsItem.makeEnumStatsItem(MetaStats.class, "Contents File Status").setWidth(120)),
		DublinCoreFileStats(StatsItem.makeEnumStatsItem(MetaStats.class, "Dublin Core File Status").setWidth(120)),
		OtherSchemas(StatsItem.makeStringStatsItem("Other Schemas")),
		OtherMetadataFileStats(StatsItem.makeEnumStatsItem(MetaStats.class, "Other Metadata File Status").setWidth(120)),
		Message(StatsItem.makeStringStatsItem("Message", 300).setExport(false))
		;
		
		StatsItem si;
		IngestStatsItems(StatsItem si) {this.si=si;}
		public StatsItem si() {return si;}
	}

	public static enum Generator implements StatsGenerator {
		INSTANCE;
		public Stats create(String key) {return new Stats(details, key);}
	}
	static StatsItemConfig details = StatsItemConfig.create(IngestStatsItems.class);
	class column {
		boolean valid;
		boolean fixed;
		int inputCol;
		String element;
		String qualifier;
		String name;
		String schema = "dc";
		
		column(int inputCol, String header) {
			this(inputCol, header, false);
		}
		column(int inputCol, String header, boolean fixed) {
			this.fixed = fixed;
			this.name = header;
			this.inputCol = inputCol;
			String[] parts = header.split("\\.");
			if ((parts.length < 2) || (parts.length > 3)) {
				valid = false;
			} else  {
				valid = true;
				schema = parts[0];
				element = parts[1];
				if (parts.length > 2) {
					qualifier = parts[2];
				} else {
					qualifier = "none";
				}
			} 
		}
		
	}
	
	private static enum FileStats {NA, NOT_FOUND, ERROR, ALREADY_EXISTS, MOVED_TO_INGEST, COPIED_TO_INGEST;}
	private static enum MetaStats {NA, ERROR, CREATED, OVERWRITTEN;}
	private static enum InputMetaStats {NA, MISSING, FORMAT_ERROR, DUPLICATE, OK;}
	class CreateException extends Exception {
		private static final long serialVersionUID = 5042987495219000857L;

		CreateException(String s) {
			super(s);
		}
	}
	
	Vector<column> colHeaderDefs;
	HashMap<String,column> colByName;
	HashMap<String,Integer> folders;
	
	public StatsItemConfig getDetails() {
		return details;
	}
	
	private enum status {INIT,PASS,WARN,FAIL}
	
	NumberFormat nf;
	
	public static final String REUSABLE_THUMBNAIL = "Reusable Thumbnail";
	public static final String REUSABLE_LICENSE = "Reusable License";
	public static enum FIXED {
		FOLDER(0), ITEM(1), THUMB(2), LICENSE(3);
		int index;
		FIXED(int i) {index = i;}
	}
	
	public IngestFolderCreate(FTDriver dt) {
		super(dt);
		nf = NumberFormat.getNumberInstance();
		nf.setMinimumIntegerDigits(8);
		nf.setGroupingUsed(false);

		this.ftprops.add(new FTPropString(dt, this.getClass().getName(), REUSABLE_THUMBNAIL, "thumb",
				"Relative path to thumbnail file to be used for all items w/o thumbnail (optional)", ""));
		this.ftprops.add(new FTPropString(dt, this.getClass().getName(), REUSABLE_LICENSE, "license",
				"Relative path to license file to be used for all items w/o license (optional)", ""));

	}

	public String toString() {
		return "Ingest: Create Ingest Folders";
	}
	public String getDescription() {
		return "This will create DSpace Ingest Folders.  Note: this action will MOVE files into the ingest folder structure.  Please save a backup of your initial directory before running this action the first time.\n"+
				"The File Test 'Ingest Inventory' can be used to create an initial spreadsheet if one does not exist.\n"+
				"File Structure\n"+
				"\t1) Folder Name - A unique folder will be created for each item to be ingested.  Names must be unique\n"+
				"\t2) Item file name - required, a file with that name must exist relative to the imported spreadsheet\n"+
				"\t3) Thumbnail file name - optional, file must exist if present\n"+
				"\t4) License file name - optional, file must exist if present\n"+
				"\tAddition columns should have a dublin core field name in their header.  Columns without a 'dc.' header will be ignored\n" +
				"A title (dc.title) and a properly formatted creation date (dc.date.created) must be present somewhere in the set of additional columns.";
	}
	public String getShortName() {
		return "Ingest Folder";
	}
	
	public void createMetadataFile(File dir, Stats stats, Vector<String> cols, String schema) {
		String filename = "dublin_core.xml";
		IngestStatsItems index = IngestStatsItems.DublinCoreFileStats;
		
		if (!schema.equals("dc")) {
			filename = "metadata_" + schema + ".xml";
			index = IngestStatsItems.OtherMetadataFileStats;
			stats.appendVal(IngestStatsItems.OtherSchemas, schema);
		} 
		
		Document d = XMLUtil.db.newDocument();
		Element e = d.createElement("dublin_core");
		e.setAttribute("schema", schema);
		d.appendChild(e);
		for(int i=0; i<cols.size(); i++) {
			String col = cols.get(i);
			if (col.isEmpty()) continue;
			column colhead = colHeaderDefs.get(i);
			if (!colhead.schema.equals(schema)) continue;
			if (colhead.valid) {
				addElement(e, colhead.element, colhead.qualifier, col);
			}
		}

		File f = new File(dir, filename);
		if (f.exists()) {
			stats.setVal(index, MetaStats.OVERWRITTEN);
		} else {
			stats.setVal(index, MetaStats.CREATED);
		}

		try {
			XMLUtil.doSerialize(d, f);
		} catch (TransformerException e2) {
			stats.setVal(IngestStatsItems.Status, status.FAIL);
			stats.setVal(index, MetaStats.ERROR);
			stats.setVal(IngestStatsItems.Message, e2.getMessage());
		} catch (IOException e2) {
			stats.setVal(IngestStatsItems.Status, status.FAIL);
			stats.setVal(index, MetaStats.ERROR);
			stats.setVal(IngestStatsItems.Message, e2.getMessage());
		}
		
	}

	public void createItem(Stats stats, File selectedFile, Vector<String> cols) {
		File dir = new File(new File(selectedFile.getParentFile(), "ingest"), cols.get(0));
		dir.mkdirs();
		
		HashSet<String> schemas = new HashSet<String>();
		for(int i=0; i<cols.size(); i++) {
			String col = cols.get(i);
			if (col.isEmpty()) continue;
			column colhead = colHeaderDefs.get(i);
			String schema = colhead.schema;
			schemas.add(schema);
		}

		for(String schema: schemas){
			createMetadataFile(dir, stats, cols, schema);
		}
		
		File f = new File(dir, "contents");
		if (f.exists()) {
			stats.setVal(IngestStatsItems.ContentsFileStats, MetaStats.OVERWRITTEN);
		} else {
			stats.setVal(IngestStatsItems.ContentsFileStats, MetaStats.CREATED);
		}
		
		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(f));
			String name = (String)stats.getKeyVal(details.getByKey(FIXED.ITEM.index), "");
			String val = (new File(name)).getName();
			bw.write(val);
			bw.write("\t");
			bw.write("bundle:ORIGINAL");
			bw.write("\n");
			
			name = (String)stats.getKeyVal(details.getByKey(FIXED.THUMB.index), "");
			val = (new File(name)).getName();
			if (!val.isEmpty()) {
				bw.write(val);
				bw.write("\t");
				bw.write("bundle:THUMBNAIL");
				bw.write("\n");				
			}
			name = (String)stats.getKeyVal(details.getByKey(FIXED.LICENSE.index), "");
			val = (new File(name)).getName();
			if (!val.isEmpty()) {
				bw.write(val);
				bw.write("\t");
				bw.write("bundle:LICENSE");
				bw.write("\n");				
			}
			bw.close();
			
		} catch (IOException e1) {
			stats.setVal(IngestStatsItems.Status, status.FAIL);
			stats.setVal(IngestStatsItems.ContentsFileStats, MetaStats.ERROR);
			stats.setVal(IngestStatsItems.Message, e1.getMessage());
		}
	}
	
	
	public void addElement(Element e, String name, String qual, String val) {
		Element el = e.getOwnerDocument().createElement("dcvalue");
		e.appendChild(el);
		el.setAttribute("element",name);
		el.setAttribute("qualifier", qual);
		el.appendChild(e.getOwnerDocument().createTextNode(val));
	}
	
	public void addColumn(column col) {
		colHeaderDefs.add(col);
		colByName.put(col.name, col);
	}
	
	public String getColumnValue(Vector<String> vals, String name, String def) {
		column col = colByName.get(name);
		if (col == null) return def;
		if (col.inputCol > vals.size()) return def;
		String val = vals.get(col.inputCol);
		if (val == null) return def;
		if (val.trim().isEmpty()) return def;
		return val.trim();
	}

	public boolean hasColumnValue(Vector<String> vals, String name) {
		return getColumnValue(vals, name, null) != null;
	}

	public ActionResult importFile(File selectedFile) throws IOException {
		Timer timer = new Timer();
		String globalThumb = (String)getProperty(REUSABLE_THUMBNAIL);
		String globalLicense = (String)getProperty(REUSABLE_LICENSE);
		TreeMap<String,Stats> types = new TreeMap<String,Stats>();
		int rowKey = 0;
		Vector<Vector<String>> data = DelimitedFileImporter.parseFile(selectedFile, "\t");
		folders = new HashMap<String,Integer>();
		colHeaderDefs = new Vector<column>();
		colByName = new HashMap<String, column>();
		Vector<String> colheads = data.get(0);
		
		addColumn(new column(FIXED.FOLDER.index, InventoryStatsItems.Key.si().header, true));
		addColumn(new column(FIXED.ITEM.index, InventoryStatsItems.File.si().header, true));
		addColumn(new column(FIXED.THUMB.index, InventoryStatsItems.ThumbFile.si().header, true));
		addColumn(new column(FIXED.LICENSE.index, InventoryStatsItems.LicenseFile.si().header, true));
		
		for(int i=colHeaderDefs.size(); i< colheads.size(); i++) {
			String colh = colheads.get(i);
			addColumn(new column(i, colh));
		}
		
		details = StatsItemConfig.create(IngestStatsItems.class); 
		for(column col: colHeaderDefs) {
			if (col.valid || col.fixed) {
				details.addStatsItem(col.inputCol, StatsItem.makeStringStatsItem(col.name, 250));
			}
		}

		for(int r=1; r<data.size(); r++) {
			Vector<String> cols = data.get(r);
			String key = nf.format(rowKey++);
			Stats stats = Generator.INSTANCE.create(key);
			importRow(selectedFile, cols, stats, globalThumb, globalLicense);
			
			types.put(key, stats);
		}
		
		return new ActionResult(selectedFile, selectedFile.getName(), this.toString(), getDetails(), types, true, timer.getDuration());
	}

	public void importRow(File selectedFile, Vector<String> cols,Stats stats, String globalThumb, String globalLicense) {
		StringBuffer buf = new StringBuffer();
		try {
			if (cols.size() != colHeaderDefs.size()) {
				throw new CreateException(cols.size()+" columns found : "+colHeaderDefs.size() + " columns expected");
			}
			String folder = "";
			String file = "";
			String thumb = "";
			String license = "";
				
			for(int i=0; i<cols.size(); i++){
				column colhead = colHeaderDefs.get(i);
				String val = cols.get(i);
				if ((colhead.fixed || colhead.valid)) {
					stats.setKeyVal(details.getByKey(i), val);
				}
					
				if (colhead.fixed) {
					if (i == FIXED.FOLDER.index) {
						if (val.equals("")) {
							stats.setVal(IngestStatsItems.InputMetadata, InputMetaStats.MISSING);
							throw new CreateException("Item folder name is required");
						}
						folder = val;
						stats.setVal(IngestStatsItems.Folder, folder);
							
						Integer x = folders.get(folder);
						if (x == null) {
							folders.put(folder, 1);								
						} else {
							folders.put(folder, x++);
							stats.setVal(IngestStatsItems.InputMetadata, InputMetaStats.DUPLICATE);
							throw new CreateException("Item folder name [" + folder + "] is a duplicate of another folder");
						}
					} else	if (i == FIXED.ITEM.index) {
						if (val.isEmpty()) {
							stats.setVal(IngestStatsItems.InputMetadata, InputMetaStats.MISSING);
							throw new CreateException("Item file name is required");
						}
						file = val;
					} else if (i == FIXED.THUMB.index) {
						thumb = val;
						if (thumb.isEmpty()) {
							if (!globalThumb.isEmpty()) {
								stats.setKeyVal(details.getByKey(i), file + ".jpg");								
							} 
						} else if (!thumb.equals(file+".jpg")){
							stats.setVal(IngestStatsItems.InputMetadata, InputMetaStats.FORMAT_ERROR);
							throw new CreateException("DSpace requires thumbnail to be named [" + file + ".jpg]");							
						}
					} else if (i == FIXED.LICENSE.index) {
						license = val;
						if (license.isEmpty() && !globalLicense.isEmpty()) {
							stats.setKeyVal(details.getByKey(i), globalLicense);
						}
					}
				}
			}				
				
			if (!hasColumnValue(cols, "dc.title")) {
				stats.setVal(IngestStatsItems.InputMetadata, InputMetaStats.MISSING);
				throw new CreateException("Item must have element [dc.title]");
			} else if (!hasColumnValue(cols, "dc.date.created")) {
				stats.setVal(IngestStatsItems.InputMetadata, InputMetaStats.MISSING);
				throw new CreateException("Item must have element [dc.date.created]");
			} else {
				for(column col: colHeaderDefs) {
					if (col.valid && col.element.equals("date")) {
						String val = getColumnValue(cols, col.name, "");
						if (!testDate(val)) {
							stats.setVal(IngestStatsItems.InputMetadata, InputMetaStats.FORMAT_ERROR);
							throw new CreateException(col.name +" [" + val + "] must start with either YYYY-MM-DD, YYYY-MM, YYYY or 'No Date'.");
						}
					}
				}
			} 
			stats.setVal(IngestStatsItems.InputMetadata, InputMetaStats.OK);				

			testFile(stats, IngestStatsItems.ItemFileStats, selectedFile, folder, file);
			if (!thumb.isEmpty()) {
				testFile(stats, IngestStatsItems.ThumbFileStats, selectedFile, folder, thumb);
			}
			if (!license.isEmpty()) {
				testFile(stats, IngestStatsItems.LicenseFileStats, selectedFile, folder, license);
			}
			if (stats.getVal(IngestStatsItems.Status) == status.FAIL) {
				return;
			}
			
			prepFile(stats, IngestStatsItems.ItemFileStats, MODE.MOVE, selectedFile, folder, file);
			
			if (!thumb.isEmpty()) {
				prepFile(stats, IngestStatsItems.ThumbFileStats, MODE.MOVE, selectedFile, folder, thumb);
			} else if (!globalThumb.isEmpty()) {
				prepFile(stats, IngestStatsItems.ThumbFileStats, MODE.COPY, selectedFile, folder, globalThumb, file + ".jpg");
			} 

			if (!license.isEmpty()) {
				prepFile(stats, IngestStatsItems.LicenseFileStats, MODE.MOVE, selectedFile, folder, license);
			} else if (!globalLicense.isEmpty()) {
				prepFile(stats, IngestStatsItems.LicenseFileStats, MODE.COPY, selectedFile, folder, globalLicense);
			}
			createItem(stats, selectedFile, cols);
			
			if (stats.getVal(IngestStatsItems.Status) != status.FAIL) {
				stats.setVal(IngestStatsItems.Status, status.PASS);				
			}
			
			stats.setVal(IngestStatsItems.Message, buf.toString());					
		} catch(CreateException e) {
			stats.setVal(IngestStatsItems.Status, status.FAIL);
			stats.setVal(IngestStatsItems.Message, e.getMessage());
		}
    }
	
	

	public boolean testDate(String s) {
		Pattern p = Pattern.compile("^(No Date|\\d\\d\\d\\d-\\d\\d-\\d\\d|\\d\\d\\d\\d-\\d\\d|\\d\\d\\d\\d)( .*)?$");
		Matcher m = p.matcher(s);
		return m.matches();
	}

	void testFile(Stats stats, IngestStatsItems sienum, File selectedFile, String folder, String file) throws CreateException {
		File parentFile = selectedFile.getParentFile();
		File ingestDir = new File(parentFile, "ingest");
		File dir = new File(ingestDir, folder);
		File f = new File(parentFile, file);
		File dest = new File(dir, (new File(file)).getName());
		if (dest.exists()) {
			stats.setVal(sienum, FileStats.ALREADY_EXISTS);
		} else if (f.exists()) {
			stats.setVal(sienum, FileStats.NA);
		} else {
			stats.setVal(sienum, FileStats.NOT_FOUND);
			stats.setVal(IngestStatsItems.Message, "File ["+file+"] does not exist");
			stats.setVal(IngestStatsItems.Status, status.FAIL);
		}
	}
	
	private File getSourceFile(File selectedFile, String file) throws CreateException {
		File parentFile = selectedFile.getParentFile();
		File f = new File(parentFile, file);
		File f2 = new File(parentFile.getAbsolutePath() + "\\" + file);
		
		if (f.exists()) {
			return f;
		} else if (f2.exists()) {
			return f2;
		}
		throw new CreateException("Required file ["+file + "] not found.");
	}
	
	private static enum MODE {MOVE,COPY;}

	void prepFile(Stats stats, IngestStatsItems sienum, MODE mode, File selectedFile, String folder, String srcname) throws CreateException {
		prepFile(stats, sienum, mode, selectedFile, folder, srcname, srcname);
	}
	
	void prepFile(Stats stats, IngestStatsItems sienum, MODE mode, File selectedFile, String folder, String srcname, String destname)  {
		try {
			File parentFile = selectedFile.getParentFile();
			File ingestDir = new File(parentFile, "ingest");
			File dir = new File(ingestDir, folder);
			dir.mkdirs();

			File dest = new File(dir, new File(destname).getName());
			if (dest.exists()) {
				stats.setVal(sienum, FileStats.ALREADY_EXISTS);
				return;
			} 
			
			File source = getSourceFile(selectedFile, srcname);
			if (mode == MODE.MOVE) {
				source.renameTo(dest);
				stats.setVal(sienum, FileStats.MOVED_TO_INGEST);
			} else if (mode == MODE.COPY) {
				FileUtil.copyFile(source, dest);
				stats.setVal(sienum, FileStats.COPIED_TO_INGEST);
			}

			if (!dest.exists()) {
				stats.setVal(IngestStatsItems.Message, "File ["+dest+"] does not exist in ingest folder");
				stats.setVal(sienum, FileStats.ERROR);
			}
		} catch (CreateException e) {
			stats.setVal(IngestStatsItems.Message, e.getMessage());
			stats.setVal(sienum, FileStats.NOT_FOUND);
			stats.setVal(IngestStatsItems.Status, status.FAIL);
		} catch (SecurityException e) {
			stats.setVal(IngestStatsItems.Message, "Cannot create ingest dir");
			stats.setVal(sienum, FileStats.ERROR);
			stats.setVal(IngestStatsItems.Status, status.FAIL);
		} catch (FileNotFoundException e) {
			stats.setVal(IngestStatsItems.Message, "File ["+srcname+"] does not exist");
			stats.setVal(sienum, FileStats.ERROR);
			stats.setVal(IngestStatsItems.Status, status.FAIL);
		} catch (IOException e) {
			stats.setVal(IngestStatsItems.Message, "File ["+srcname+"] cannot be copied");
			stats.setVal(sienum, FileStats.ERROR);
			stats.setVal(IngestStatsItems.Status, status.FAIL);
		}
	}

}