package com.zmtech.zkit.etl;

import javax.annotation.Nonnull;
import java.util.*;

@SuppressWarnings("unused")
public class SimpleEtl {
    private Extractor extractor;
    private TransformConfiguration internalConfig = null;
    private Loader loader;

    private List<String> messages = new LinkedList<>();
    private Exception extractException = null;
    private List<EtlError> transformErrors = new LinkedList<>();
    private List<EtlError> loadErrors = new LinkedList<>();
    private boolean stopOnError = false;
    private Integer timeout = 3600; // default to one hour

    private int extractCount = 0, skipCount = 0, loadCount = 0;
    private long startTime = 0, endTime = 0;

    public SimpleEtl(Extractor extractor, Loader loader) {
        this.extractor = extractor;
        this.loader = loader;
    }


    /** 调用此方法添加运行任何类型转换器，将按顺序运行添加 */
    public SimpleEtl addTransformer(Transformer transformer) {
        if (internalConfig == null) internalConfig = new TransformConfiguration();
        internalConfig.addTransformer(transformer);
        return this;
    }
    /** 调用此方法添加运行指定类型转换器，将按顺序运行添加 */
    public SimpleEtl addTransformer(String type, Transformer transformer) {
        if (internalConfig == null) internalConfig = new TransformConfiguration();
        internalConfig.addTransformer(type, transformer);
        return this;
    }
    /** 从外部TransformConfiguration添加，复制配置以避免修改 */
    public SimpleEtl addConfiguration(TransformConfiguration config) {
        if (internalConfig == null) internalConfig = new TransformConfiguration();
        internalConfig.copyFrom(config);
        return this;
    }
    /**
     * 从外部TransformConfiguration设置。
     * 覆盖任何先前的addTransformer（）和addConfiguration（）。
     * 对addTransformer（）和addConfiguration（）的任何调用都将修改此配置。
     */
    public SimpleEtl setConfiguration(TransformConfiguration config) {
        internalConfig = config;
        return this;
    }
    /** 设置错误停止标志 */
    public SimpleEtl stopOnError() { this.stopOnError = true; return this; }
    /** 设置超时秒数; 传递给Loader.init（）进行传输等用途 */
    public SimpleEtl setTimeout(Integer timeout) { this.timeout = timeout; return this; }

    /** 调用此方法来处理ETL */
    public SimpleEtl process() {
        startTime = System.currentTimeMillis();
        // initialize loader
        loader.init(timeout);

        try {
            // kick off extraction to process extracted entries
            extractor.extract(this);
        } catch (Exception e) {
            extractException = e;
        } finally {
            // close the loader
            loader.complete(this);
            endTime = System.currentTimeMillis();
        }

        return this;
    }

    public Extractor getExtractor() { return extractor; }
    public Loader getLoader() { return loader; }

    public SimpleEtl addMessage(String msg) { this.messages.add(msg); return this; }
    public List<String> getMessages() { return Collections.unmodifiableList(messages); }
    public int getExtractCount() { return extractCount; }
    public int getSkipCount() { return skipCount; }
    public int getLoadCount() { return loadCount; }
    public long getRunTime() { return endTime - startTime; }

    public Exception getExtractException() { return extractException; }
    public List<EtlError> getTransformErrors() { return Collections.unmodifiableList(transformErrors); }
    public List<EtlError> getLoadErrors() { return Collections.unmodifiableList(loadErrors); }
    public boolean hasError() { return extractException != null || transformErrors.size() > 0 || loadErrors.size() > 0; }
    public Throwable getSingleErrorCause() {
        if (extractException != null) return extractException;
        if (transformErrors.size() > 0) return transformErrors.get(0).error;
        if (loadErrors.size() > 0) return loadErrors.get(0).error;
        return null;
    }

    /**
     * 由提取器调用以处理提取的数据。
     * @return 如果数据加载，则为true，否则为false
     * @throws StopException 如果抛出提取应停止并返回
     */
    public boolean processEntry(Entry extractEntry) throws StopException {
        if (extractEntry == null) return false;
        extractCount++;
        ArrayList<Entry> loadEntries = new ArrayList<Entry>();

        if (internalConfig != null && internalConfig.hasTransformers) {
            EntryTransform entryTransform = new EntryTransform(extractEntry);
            internalConfig.runTransformers(this, entryTransform, loadEntries);
            if (entryTransform.loadCurrent != null ? entryTransform.loadCurrent :
                    entryTransform.newEntries == null || entryTransform.newEntries.size() == 0) {
                loadEntries.add(0, entryTransform.entry);
            } else if (entryTransform.newEntries == null || entryTransform.newEntries.size() == 0) {
                skipCount++;
                return false;
            }
        } else {
            loadEntries.add(extractEntry);
        }

        int loadEntriesSize = loadEntries.size();
        for (int i = 0; i < loadEntriesSize; i++) {
            Entry loadEntry = loadEntries.get(i);
            try {
                loader.load(loadEntry);
                loadCount++;
            } catch (Throwable t) {
                loadErrors.add(new EtlError(loadEntry, t));
                if (stopOnError) throw new StopException(t);
                return false;
            }
        }
        return true;
    }

    public static class TransformConfiguration {
        private ArrayList<Transformer> anyTransformers = new ArrayList<>();
        private int anyTransformerSize = 0;
        private LinkedHashMap<String, ArrayList<Transformer>> typeTransformers = new LinkedHashMap<>();
        boolean hasTransformers = false;

        public TransformConfiguration() { }

        /** 调用此方法添加一个转换器以运行任何类型，它将按顺序运行 */
        public TransformConfiguration addTransformer(@Nonnull Transformer transformer) {
            anyTransformers.add(transformer);
            anyTransformerSize = anyTransformers.size();
            hasTransformers = true;
            return this;
        }
        /** 调用此方法添加一个转换器以运行特定类型，它将按顺序运行 */
        public TransformConfiguration addTransformer(@Nonnull String type, @Nonnull Transformer transformer) {
            typeTransformers.computeIfAbsent(type, k -> new ArrayList<>()).add(transformer);
            hasTransformers = true;
            return this;
        }

        // 返回true以跳过该数据（或从加载列表中删除）
        void runTransformers(SimpleEtl etl, EntryTransform entryTransform, ArrayList<Entry> loadEntries) throws StopException {
            for (int i = 0; i < anyTransformerSize; i++) {
                transformEntry(etl, anyTransformers.get(i), entryTransform);
            }
            String curType = entryTransform.entry.getEtlType();
            if (curType != null && curType.length() != 0) {
                ArrayList<Transformer> curTypeTrans = typeTransformers.get(curType);
                int curTypeTransSize = curTypeTrans != null ? curTypeTrans.size() : 0;
                for (int i = 0; i < curTypeTransSize; i++) {
                    transformEntry(etl, curTypeTrans.get(i), entryTransform);
                }
            }
            // 处理新数据，运行转换，然后添加到加载列表，如果没有跳过
            int newEntriesSize = entryTransform.newEntries != null ? entryTransform.newEntries.size() : 0;
            for (int i = 0; i < newEntriesSize; i++) {
                Entry newEntry = entryTransform.newEntries.get(i);
                if (newEntry == null) continue;

                EntryTransform newTransform = new EntryTransform(newEntry);
                runTransformers(etl, newTransform, loadEntries);
                if (newTransform.loadCurrent != null ? newTransform.loadCurrent : newTransform.newEntries == null || newTransform.newEntries.size() == 0) {
                    loadEntries.add(newEntry);
                }
            }
        }
        // 内部方法，返回true以跳过数据（或从加载列表中删除）
        void transformEntry(SimpleEtl etl, Transformer transformer, EntryTransform entryTransform) throws StopException {
            try {
                transformer.transform(entryTransform);
            } catch (Throwable t) {
                etl.transformErrors.add(new EtlError(entryTransform.entry, t));
                if (etl.stopOnError) throw new StopException(t);
                entryTransform.loadCurrent(false);
            }
        }

        void copyFrom(TransformConfiguration conf) {
            if (conf == null) return;
            anyTransformers.addAll(conf.anyTransformers);
            anyTransformerSize = anyTransformers.size();
            for (Map.Entry<String, ArrayList<Transformer>> entry : conf.typeTransformers.entrySet()) {
                typeTransformers.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
            }
        }
    }

    public static class StopException extends Exception {
        public StopException(Throwable t) { super(t); }
    }

    public static class EtlError {
        public final Entry entry;
        public final Throwable error;
        EtlError(Entry entry, Throwable t) { this.entry = entry; this.error = t; }
    }

    public interface Entry {
        String getEtlType();
        Map<String, Object> getEtlValues();
    }
    public static class SimpleEntry implements Entry {
        public final String type;
        public final Map<String, Object> values;
        public SimpleEntry(String type, Map<String, Object> values) { this.type = type; this.values = values; }
        @Override public String getEtlType() { return type; }
        @Override public Map<String, Object> getEtlValues() { return values; }
        // TODO: 添加equals和hash覆盖
    }
    public static class EntryTransform {
        final Entry entry;
        ArrayList<Entry> newEntries = null;
        Boolean loadCurrent = null;
        EntryTransform(Entry entry) { this.entry = entry; }
        /** 获取当前数据以获取类型并根据需要获取/放置值 */
        public Entry getEntry() { return entry; }
        /**
         * 默认情况下，仅在未添加新数据时才添加当前数据;
         * 设置为false即使需要添加数据也不添加（过滤）;
         * 设置为true即使没有需要添加的新数据也会添加
         */
        public EntryTransform loadCurrent(boolean load) { loadCurrent = load; return this; }
        /** Add a new entry to be transformed and if not filtered then loaded */
        public EntryTransform addEntry(Entry newEntry) {
            if (newEntries == null) newEntries = new ArrayList<>();
            newEntries.add(newEntry);
            return this;
        }
    }

    public interface Extractor {
        /** 调用一次开始处理，应该为每个数据调用etl.processEntry（）并在完成后关闭它自己 */
        void extract(SimpleEtl etl) throws Exception;
    }
    /** 无状态ETL入口变压器和滤波器接口 */
    public interface Transformer {
        /** 调用EntryTransform上的方法以添加新数据（通常使用不同类型），修改当前数据的值或过滤数据。 */
        void transform(EntryTransform entryTransform) throws Exception;
    }
    public interface Loader {
        /** 在SimpleEtl处理开始之前调用 */
        void init(Integer timeout);
        /** 将单个可选的已转换数据加载到数据目标中 */
        void load(Entry entry) throws Exception;
        /** 在处理完所有条目以关闭文件，提交/回滚事务等之后的调用;  */
        void complete(SimpleEtl etl);
    }
}

