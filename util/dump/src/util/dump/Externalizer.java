package util.dump;

import java.io.DataInput;
import java.io.EOFException;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.OutputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import util.dump.stream.ExternalizableObjectOutputStream;
import util.reflection.FieldAccessor;
import util.reflection.FieldFieldAccessor;
import util.reflection.MethodFieldAccessor;
import util.reflection.UnsafeFieldFieldAccessor;


/**
 * This class provides an easy way to make beans <code>Externalizable</code>.<p/>
 *
 * All you have to do is extends this class with your bean and add the <code>@externalize</code> annotation
 * for each field or getter setter pair. This annotation has a parameter where you set unique, non-reusable indexes.
 *
 * The serialization and deserialization works even with different revisions of your bean. It is both downward and upward
 * compatible, i.e. you can add and remove fields or getter setter pairs as you like and your binary representation will
 * stay readable by both the new and the old version of your bean.<p/>
 *
 * <b>Limitations:</b>
 * <ul><li>
 * Downward and upward compatibility will not work, if you reuse indexes between different revisions of your bean.
 * </li><li>
 * While externalization with this method is about 3-6 times faster than serialization (depending on the amount
 * of non-primitive or array members), hand written externalization is still about 40% faster, because no reflection
 * is used and upwards/downwards compatibility is not taken care of. This method of serialization is a bit faster 
 * than Google's protobuffers, unless your object graph is complex, i.e. you have fields containing <code>Externalizable</code> 
 * instances. For optimal performance use jre 1.6 and the <code>-server</code> switch.
 * </li><li>
 * All types are allowed for your members, but if your member is not included in the following list of supported
 * types, serialization falls back to normal java.io.Serializable mechanisms by using {@link ObjectOutput#writeObject(Object)},
 * which are slow and break downward and upward compatibility.
 * These are the supported types (see also {@link Externalizer.FieldType}):
 * <ul><li>
 * primitive fields (<code>int</code>, <code>float</code>, ...) and single-dimensional arrays containing primitives
 * </li><li>
 * all <code>Number</code> classes (<code>Integer</code>, <code>Float</code>, ...)
 * </li><li>
 * <code>String</code> and <code>String[]</code>
 * </li><li>
 * <code>Date</code> and <code>Date[]</code>
 * </li><li>
 * single and two-dimensional arrays of any <code>Externalizable</code>
 * </li><li>
 * generic Lists of any <code>Externalizable</code> type, i.e. <code>List&lt;Externalizable&gt;</code>
 * </li><li>
 * Enums and {@link EnumSet}s, as long as the enum has less than 64 values. 
 * </li>
 * </ul>
 * Currently unsupported (i.e. slow and not compatible with {@link util.dump.stream.SingleTypeObjectStreamProvider})
 * are multi-dimensional primitive arrays, any array of <code>Numbers</code>, multi-dim <code>String</code> or
 * <code>Date</code> arrays, and <code>Maps</code>.<p/>
 * </li><li>
 * While annotated fields can be any of public, protected, package protected or private, annotated methods must be public.
 * </li><li>
 * Unless the system property <code>Externalizer.USE_UNSAFE_FIELD_ACCESSORS</code> is set to <code>false</code>
 * an incredibly daring hack is used for making field access using reflection faster. That's why you should 
 * annotate fields rather than methods, unless you need some transformation before or after serialization. 
 * </li><li>
 * Enums and {@link EnumSet}s are not really downward and upward compatible: you can only add enum values at the end. 
 * Reordering or deleting values breaks downward and upward compatibility! 
 * </li>
 * </ul>
 * @see {@link util.dump.ExternalizerTest}
 */
public class Externalizer implements Externalizable {

   protected static final long              serialVersionUID           = -1816997029156670474L;

   private static boolean                   USE_UNSAFE_FIELD_ACCESSORS = true;
   private static Map<Class, ClassConfig>   CLASS_CONFIGS              = new HashMap<Class, ClassConfig>();
   private static ThreadLocal<BytesCache>   BYTES_CACHE                = new ThreadLocal<BytesCache>() {

                                                                          @Override
                                                                          protected BytesCache initialValue() {
                                                                             return new BytesCache();
                                                                          }
                                                                       };
   private static ThreadLocal<ObjectOutput> CACHING_OUT                = new ThreadLocal<ObjectOutput>() {

                                                                          @Override
                                                                          protected ObjectOutput initialValue() {
                                                                             try {
                                                                                return new ExternalizableObjectOutputStream(BYTES_CACHE.get());
                                                                             }
                                                                             catch ( IOException argh ) {
                                                                                // insane, cannot happen
                                                                                return null;
                                                                             }
                                                                          };
                                                                       };

   static {
      try {
         boolean config = Boolean.parseBoolean(System.getProperty("Externalizer.USE_UNSAFE_FIELD_ACCESSORS", "true"));
         USE_UNSAFE_FIELD_ACCESSORS &= config;
         Class.forName("sun.misc.Unsafe");
      }
      catch ( Exception argh ) {
         USE_UNSAFE_FIELD_ACCESSORS = false;
      }
   }

   private ClassConfig                      _config;


   public Externalizer() {
      init();
   }

   public void readExternal( ObjectInput in ) throws IOException, ClassNotFoundException {
      try {
         int fieldNumberToRead = in.readByte();

         FieldAccessor[] fieldAccessors = _config._fieldAccessors;
         byte[] fieldIndexes = _config._fieldIndexes;
         FieldType[] fieldTypes = _config._fieldTypes;
         Class[] defaultTypes = _config._defaultTypes;
         int j = 0;
         for ( int i = 0; i < fieldNumberToRead; i++ ) {
            byte fieldIndex = in.readByte();
            byte fieldTypeId = in.readByte();

            /* We expect fields to be stored in ascending fieldIndex order.
             * That's why we can find the appropriate fieldIndex in our sorted fieldIndexes array by skipping. */
            while ( fieldIndexes[j] < fieldIndex && j < fieldIndexes.length - 1 ) {
               j++;
            }

            FieldAccessor f = null;
            FieldType ft = null;
            Class defaultType = null;
            if ( fieldIndexes[j] == fieldIndex ) {
               f = fieldAccessors[j];
               ft = fieldTypes[j];
               defaultType = defaultTypes[j];
            } else { // unknown field
               ft = FieldType.forId(fieldTypeId);
            }

            if ( ft.isLengthDynamic() ) {
               int size = in.readInt();
               if ( f == null ) {
                  in.skipBytes(size);
                  continue;
               }
            }

            switch ( ft ) {
            case pInt: {
               int d = in.readInt();
               if ( f != null ) {
                  f.setInt(this, d);
               }
               break;
            }
            case pBoolean: {
               boolean d = in.readBoolean();
               if ( f != null ) {
                  f.setBoolean(this, d);
               }
               break;
            }
            case pByte: {
               byte d = in.readByte();
               if ( f != null ) {
                  f.setByte(this, d);
               }
               break;
            }
            case pChar: {
               char d = in.readChar();
               if ( f != null ) {
                  f.setChar(this, d);
               }
               break;
            }
            case pDouble: {
               double d = in.readDouble();
               if ( f != null ) {
                  f.setDouble(this, d);
               }
               break;
            }
            case pFloat: {
               float d = in.readFloat();
               if ( f != null ) {
                  f.setFloat(this, d);
               }
               break;
            }
            case pLong: {
               long d = in.readLong();
               if ( f != null ) {
                  f.setLong(this, d);
               }
               break;
            }
            case pShort: {
               short d = in.readShort();
               if ( f != null ) {
                  f.setShort(this, d);
               }
               break;
            }
            case String: {
               String s = readString(in);
               if ( f != null ) {
                  f.set(this, s);
               }
               break;
            }
            case Date: {
               Date d = readDate(in);
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case UUID: {
               UUID uuid = readUUID(in);
               if ( f != null ) {
                  f.set(this, uuid);
               }
               break;
            }
            case Externalizable: {
               Externalizable instance = null;
               boolean isNotNull = in.readBoolean();
               if ( isNotNull ) {
                  Class<? extends Externalizable> c = in.readBoolean() ? defaultType : forName(in.readUTF());
                  instance = c.newInstance();
                  instance.readExternal(in);
               }
               if ( f != null ) {
                  f.set(this, instance);
               }
               break;
            }
            case Integer: {
               Integer d = null;
               boolean isNotNull = in.readBoolean();
               if ( isNotNull ) {
                  d = in.readInt();
               }
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case Boolean: {
               Boolean d = null;
               boolean isNotNull = in.readBoolean();
               if ( isNotNull ) {
                  d = in.readBoolean();
               }
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case Byte: {
               Byte d = null;
               boolean isNotNull = in.readBoolean();
               if ( isNotNull ) {
                  d = in.readByte();
               }
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case Character: {
               Character d = null;
               boolean isNotNull = in.readBoolean();
               if ( isNotNull ) {
                  d = in.readChar();
               }
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case Double: {
               Double d = null;
               boolean isNotNull = in.readBoolean();
               if ( isNotNull ) {
                  d = in.readDouble();
               }
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case Float: {
               Float d = null;
               boolean isNotNull = in.readBoolean();
               if ( isNotNull ) {
                  d = in.readFloat();
               }
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case Long: {
               Long d = null;
               boolean isNotNull = in.readBoolean();
               if ( isNotNull ) {
                  d = in.readLong();
               }
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case Short: {
               Short d = null;
               boolean isNotNull = in.readBoolean();
               if ( isNotNull ) {
                  d = in.readShort();
               }
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case pByteArray: {
               byte[] d = readByteArray(in);
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case pByteArrayArray: {
               byte[][] d = null;
               boolean isNotNull = in.readBoolean();
               if ( isNotNull ) {
                  int size = in.readInt();
                  d = new byte[size][];
                  for ( int k = 0; k < size; k++ ) {
                     d[k] = readByteArray(in);
                  }
               }
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case pDoubleArray: {
               double[] d = readDoubleArray(in);
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case pDoubleArrayArray: {
               double[][] d = null;
               boolean isNotNull = in.readBoolean();
               if ( isNotNull ) {
                  int size = in.readInt();
                  d = new double[size][];
                  for ( int k = 0; k < size; k++ ) {
                     d[k] = readDoubleArray(in);
                  }
               }
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case pFloatArray: {
               float[] d = readFloatArray(in);
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case pFloatArrayArray: {
               float[][] d = null;
               boolean isNotNull = in.readBoolean();
               if ( isNotNull ) {
                  int size = in.readInt();
                  d = new float[size][];
                  for ( int k = 0; k < size; k++ ) {
                     d[k] = readFloatArray(in);
                  }
               }
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case pIntArray: {
               int[] d = readIntArray(in);
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case pIntArrayArray: {
               int[][] d = null;
               boolean isNotNull = in.readBoolean();
               if ( isNotNull ) {
                  int size = in.readInt();
                  d = new int[size][];
                  for ( int k = 0; k < size; k++ ) {
                     d[k] = readIntArray(in);
                  }
               }
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case pLongArray: {
               long[] d = readLongArray(in);
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case pLongArrayArray: {
               long[][] d = null;
               boolean isNotNull = in.readBoolean();
               if ( isNotNull ) {
                  int size = in.readInt();
                  d = new long[size][];
                  for ( int k = 0; k < size; k++ ) {
                     d[k] = readLongArray(in);
                  }
               }
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case StringArray: {
               String[] d = readStringArray(in);
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case StringArrayArray: {
               String[][] d = null;
               boolean isNotNull = in.readBoolean();
               if ( isNotNull ) {
                  int size = in.readInt();
                  d = new String[size][];
                  for ( int k = 0; k < size; k++ ) {
                     d[k] = readStringArray(in);
                  }
               }
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case DateArray: {
               Date[] d = readDateArray(in);
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case DateArrayArray: {
               Date[][] d = null;
               boolean isNotNull = in.readBoolean();
               if ( isNotNull ) {
                  int size = in.readInt();
                  d = new Date[size][];
                  for ( int k = 0; k < size; k++ ) {
                     d[k] = readDateArray(in);
                  }
               }
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case ListOfExternalizables: {
               readListOfExternalizables(in, f);
               break;
            }
            case ListOfStrings: {
               readListOfStrings(in, f);
               break;
            }
            case ExternalizableArray: {
               Externalizable[] d = readExternalizableArray(in, defaultType);
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case ExternalizableArrayArray: {
               Externalizable[][] d = null;
               boolean isNotNull = in.readBoolean();
               if ( isNotNull ) {
                  int size = in.readInt();
                  Class externalizableClass = Array.newInstance(defaultType, 0).getClass();
                  d = (Externalizable[][])Array.newInstance(externalizableClass, size);
                  for ( int k = 0, length = d.length; k < length; k++ ) {
                     d[k] = readExternalizableArray(in, defaultType);
                  }
               }
               if ( f != null ) {
                  f.set(this, d);
               }
               break;
            }
            case Enum: {
               Enum e = null;
               boolean isNotNull = in.readBoolean();
               if ( isNotNull ) {
                  if ( f != null ) {
                     Class<? extends Enum> c = f.getType();
                     Enum[] values = c.getEnumConstants();
                     int b = in.readInt();
                     if ( b < values.length ) {
                        e = values[b];
                     }
                  } else {
                     in.readInt();
                  }
               }
               if ( f != null ) {
                  f.set(this, e);
               }
               break;
            }
            case EnumSet: {
               EnumSet enumSet = null;
               boolean isNotNull = in.readBoolean();
               if ( isNotNull ) {
                  if ( f != null ) {
                     Class<? extends Enum> c = f.getGenericTypes()[0];
                     enumSet = EnumSet.noneOf(c);
                     Enum[] values = c.getEnumConstants();
                     long l = in.readLong();
                     for ( int k = 0, length = values.length; k < length; k++ ) {
                        if ( (l & (1 << k)) != 0 ) {
                           enumSet.add(values[k]);
                        }
                     }
                  } else {
                     in.readLong();
                  }
               }
               if ( f != null ) {
                  f.set(this, enumSet);
               }
               break;
            }
            default: {
               Object o = null;
               boolean isNotNull = in.readBoolean();
               if ( isNotNull ) {
                  o = in.readObject();
               }
               if ( f != null ) {
                  f.set(this, o);
               }
               //               throw new IllegalArgumentException("The field type " + fieldTypes[i] + " in class " + getClass()
               //                  + " is unsupported by util.dump.Externalizer.");
            }
            }
         }
      }
      catch ( EOFException e ) {
         throw e;
      }
      catch ( Throwable e ) {
         throw new RuntimeException("Failed to read externalized instance. Maybe the field order was changed? class " + getClass(), e);
      }
   }

   public void writeExternal( ObjectOutput out ) throws IOException {
      try {
         ObjectOutput originalOut = out;
         BytesCache bytesCache = null;
         ObjectOutput cachingOut = null;

         FieldAccessor[] fieldAccessors = _config._fieldAccessors;
         byte[] fieldIndexes = _config._fieldIndexes;
         FieldType[] fieldTypes = _config._fieldTypes;
         Class[] defaultTypes = _config._defaultTypes;
         out.writeByte(fieldAccessors.length);
         for ( int i = 0, length = fieldAccessors.length; i < length; i++ ) {
            FieldAccessor f = fieldAccessors[i];
            FieldType ft = fieldTypes[i];
            Class defaultType = defaultTypes[i];

            out.writeByte(fieldIndexes[i]);
            out.writeByte(ft._id);

            if ( ft.isLengthDynamic() ) {
               if ( bytesCache == null ) {
                  bytesCache = BYTES_CACHE.get();
                  cachingOut = CACHING_OUT.get();
               }
               bytesCache.reset();
               out = cachingOut;
            }

            switch ( ft ) {
            case pInt: {
               out.writeInt(f.getInt(this));
               break;
            }
            case pBoolean: {
               out.writeBoolean(f.getBoolean(this));
               break;
            }
            case pByte: {
               out.writeByte(f.getByte(this));
               break;
            }
            case pChar: {
               out.writeChar(f.getChar(this));
               break;
            }
            case pDouble: {
               out.writeDouble(f.getDouble(this));
               break;
            }
            case pFloat: {
               out.writeFloat(f.getFloat(this));
               break;
            }
            case pLong: {
               out.writeLong(f.getLong(this));
               break;
            }
            case pShort: {
               out.writeShort(f.getShort(this));
               break;
            }
            case String: {
               String s = (String)f.get(this);
               writeString(out, s);
               break;
            }
            case Date: {
               Date s = (Date)f.get(this);
               writeDate(out, s);
               break;
            }
            case UUID: {
               UUID u = (UUID)f.get(this);
               writeUUID(out, u);
               break;
            }
            case Externalizable: {
               Externalizable instance = (Externalizable)f.get(this);
               out.writeBoolean(instance != null);
               if ( instance != null ) {
                  Class c = instance.getClass();
                  boolean isDefault = c.equals(defaultType);
                  out.writeBoolean(isDefault);
                  if ( !isDefault ) {
                     out.writeUTF(c.getName());
                  }
                  instance.writeExternal(out);
               }
               break;
            }
            case Integer: {
               Integer s = (Integer)f.get(this);
               out.writeBoolean(s != null);
               if ( s != null ) {
                  out.writeInt(s);
               }
               break;
            }
            case Boolean: {
               Boolean s = (Boolean)f.get(this);
               out.writeBoolean(s != null);
               if ( s != null ) {
                  out.writeBoolean(s);
               }
               break;
            }
            case Byte: {
               Byte s = (Byte)f.get(this);
               out.writeBoolean(s != null);
               if ( s != null ) {
                  out.writeByte(s);
               }
               break;
            }
            case Character: {
               Character s = (Character)f.get(this);
               out.writeBoolean(s != null);
               if ( s != null ) {
                  out.writeChar(s);
               }
               break;
            }
            case Double: {
               Double s = (Double)f.get(this);
               out.writeBoolean(s != null);
               if ( s != null ) {
                  out.writeDouble(s);
               }
               break;
            }
            case Float: {
               Float s = (Float)f.get(this);
               out.writeBoolean(s != null);
               if ( s != null ) {
                  out.writeFloat(s);
               }
               break;
            }
            case Long: {
               Long s = (Long)f.get(this);
               out.writeBoolean(s != null);
               if ( s != null ) {
                  out.writeLong(s);
               }
               break;
            }
            case Short: {
               Short s = (Short)f.get(this);
               out.writeBoolean(s != null);
               if ( s != null ) {
                  out.writeShort(s);
               }
               break;
            }
            case pByteArray: {
               byte[] d = (byte[])f.get(this);
               writeByteArray(d, out);
               break;
            }
            case pByteArrayArray: {
               byte[][] d = (byte[][])f.get(this);
               out.writeBoolean(d != null);
               if ( d != null ) {
                  out.writeInt(d.length);
                  for ( byte[] dd : d ) {
                     writeByteArray(dd, out);
                  }
               }
               break;
            }
            case pDoubleArray: {
               double[] d = (double[])f.get(this);
               writeDoubleArray(d, out);
               break;
            }
            case pDoubleArrayArray: {
               double[][] d = (double[][])f.get(this);
               out.writeBoolean(d != null);
               if ( d != null ) {
                  out.writeInt(d.length);
                  for ( double[] dd : d ) {
                     writeDoubleArray(dd, out);
                  }
               }
               break;
            }
            case pFloatArray: {
               float[] d = (float[])f.get(this);
               writeFloatArray(d, out);
               break;
            }
            case pFloatArrayArray: {
               float[][] d = (float[][])f.get(this);
               out.writeBoolean(d != null);
               if ( d != null ) {
                  out.writeInt(d.length);
                  for ( float[] dd : d ) {
                     writeFloatArray(dd, out);
                  }
               }
               break;
            }
            case pIntArray: {
               int[] d = (int[])f.get(this);
               writeIntArray(d, out);
               break;
            }
            case pIntArrayArray: {
               int[][] d = (int[][])f.get(this);
               out.writeBoolean(d != null);
               if ( d != null ) {
                  out.writeInt(d.length);
                  for ( int[] dd : d ) {
                     writeIntArray(dd, out);
                  }
               }
               break;
            }
            case pLongArray: {
               long[] d = (long[])f.get(this);
               writeLongArray(d, out);
               break;
            }
            case pLongArrayArray: {
               long[][] d = (long[][])f.get(this);
               out.writeBoolean(d != null);
               if ( d != null ) {
                  out.writeInt(d.length);
                  for ( long[] dd : d ) {
                     writeLongArray(dd, out);
                  }
               }
               break;
            }
            case StringArray: {
               String[] d = (String[])f.get(this);
               writeStringArray(d, out);
               break;
            }
            case StringArrayArray: {
               String[][] d = (String[][])f.get(this);
               out.writeBoolean(d != null);
               if ( d != null ) {
                  out.writeInt(d.length);
                  for ( String[] dd : d ) {
                     writeStringArray(dd, out);
                  }
               }
               break;
            }
            case DateArray: {
               Date[] d = (Date[])f.get(this);
               writeDateArray(d, out);
               break;
            }
            case DateArrayArray: {
               Date[][] d = (Date[][])f.get(this);
               out.writeBoolean(d != null);
               if ( d != null ) {
                  out.writeInt(d.length);
                  for ( Date[] dd : d ) {
                     writeDateArray(dd, out);
                  }
               }
               break;
            }
            case ListOfExternalizables: {
               writeListOfExternalizables(out, f);
               break;
            }
            case ListOfStrings: {
               writeListOfStrings(out, f);
               break;
            }
            case ExternalizableArray: {
               Externalizable[] d = (Externalizable[])f.get(this);
               writeExternalizableArray(out, d, defaultType);
               break;
            }
            case ExternalizableArrayArray: {
               Externalizable[][] d = (Externalizable[][])f.get(this);
               out.writeBoolean(d != null);
               if ( d != null ) {
                  out.writeInt(d.length);
                  for ( int j = 0, llength = d.length; j < llength; j++ ) {
                     writeExternalizableArray(out, d[j], defaultType);
                  }
               }
               break;
            }
            case Enum: {
               Enum e = (Enum)f.get(this);
               out.writeBoolean(e != null);
               if ( e != null ) {
                  Class<? extends Enum> c = f.getType();
                  Enum[] values = c.getEnumConstants();
                  int b = -1;
                  for ( int j = 0, llength = values.length; j < llength; j++ ) {
                     if ( e == values[j] ) {
                        b = j;
                     }
                  }
                  out.writeInt(b);
               }
               break;
            }
            case EnumSet: {
               EnumSet enumSet = (EnumSet)f.get(this);
               out.writeBoolean(enumSet != null);
               if ( enumSet != null ) {
                  Class<? extends Enum> c = f.getGenericTypes()[0];
                  Enum[] values = c.getEnumConstants();
                  if ( values.length > 64 ) {
                     throw new IllegalArgumentException("Enum " + c + " has more than 64 values. This is unsupported by Externalizer.");
                  }
                  long l = 0;
                  for ( int j = 0, llength = values.length; j < llength; j++ ) {
                     if ( enumSet.contains(values[j]) ) {
                        l |= 1 << j;
                     }
                  }
                  out.writeLong(l);
               }
               break;
            }
            default:
               Object d = f.get(this);
               out.writeBoolean(d != null);
               if ( d != null ) {
                  out.writeObject(d);
               }
               //               throw new IllegalArgumentException("The field type " + fieldTypes[i] + " in class " + getClass()
               //                  + " is unsupported by util.dump.Externalizer.");
            }

            if ( ft.isLengthDynamic() ) {
               out.flush();
               originalOut.writeInt(bytesCache.size());
               bytesCache.writeTo(originalOut);
               out = originalOut;
            }
         }
      }
      catch ( Exception e ) {
         throw new RuntimeException("Failed to externalize class " + getClass().getName(), e);
      }
   }

   private final Class<? extends Externalizable> forName( String className ) throws ClassNotFoundException {
      return (Class<? extends Externalizable>)Class.forName(className, true, _config._classLoader);
   }

   private void init() {
      _config = CLASS_CONFIGS.get(getClass());
      if ( _config == null ) {
         _config = new ClassConfig(getClass());
         synchronized ( CLASS_CONFIGS ) {
            CLASS_CONFIGS.put(getClass(), _config);
         }
      }
   }

   private byte[] readByteArray( DataInput in ) throws IOException {
      byte[] d = null;
      boolean isNotNull = in.readBoolean();
      if ( isNotNull ) {
         d = new byte[in.readInt()];
         for ( int k = 0, length = d.length; k < length; k++ ) {
            d[k] = in.readByte();
         }
      }
      return d;
   }

   private Date readDate( ObjectInput in ) throws IOException {
      Date d = null;
      boolean isNotNull = in.readBoolean();
      if ( isNotNull ) {
         d = new Date(in.readLong());
      }
      return d;
   }

   private Date[] readDateArray( ObjectInput in ) throws IOException {
      Date[] d = null;
      boolean isNotNull = in.readBoolean();
      if ( isNotNull ) {
         d = new Date[in.readInt()];
         for ( int k = 0, length = d.length; k < length; k++ ) {
            d[k] = readDate(in);
         }
      }
      return d;
   }

   private double[] readDoubleArray( DataInput in ) throws IOException {
      double[] d = null;
      boolean isNotNull = in.readBoolean();
      if ( isNotNull ) {
         d = new double[in.readInt()];
         for ( int k = 0, length = d.length; k < length; k++ ) {
            d[k] = in.readDouble();
         }
      }
      return d;
   }

   private Externalizable[] readExternalizableArray( ObjectInput in, Class defaultType ) throws Exception {
      Externalizable[] d = null;
      boolean isNotNull = in.readBoolean();
      if ( isNotNull ) {
         int size = in.readInt();
         Class<? extends Externalizable> externalizableClass = defaultType;
         d = (Externalizable[])Array.newInstance(externalizableClass, size);
         Class lastNonDefaultClass = null;
         for ( int k = 0; k < size; k++ ) {
            Externalizable instance = null;
            isNotNull = in.readBoolean();
            if ( isNotNull ) {
               boolean isDefaultType = in.readBoolean();
               if ( isDefaultType ) {
                  instance = externalizableClass.newInstance();
               } else {
                  boolean isSameAsLastNonDefault = in.readBoolean();
                  Class c;
                  if ( isSameAsLastNonDefault ) {
                     c = lastNonDefaultClass;
                  } else {
                     c = forName(in.readUTF());
                     lastNonDefaultClass = c;
                  }
                  instance = (Externalizable)c.newInstance();
               }
               instance.readExternal(in);
            }
            d[k] = instance;
         }
      }
      return d;
   }

   private float[] readFloatArray( DataInput in ) throws IOException {
      float[] d = null;
      boolean isNotNull = in.readBoolean();
      if ( isNotNull ) {
         d = new float[in.readInt()];
         for ( int k = 0, length = d.length; k < length; k++ ) {
            d[k] = in.readFloat();
         }
      }
      return d;
   }

   private int[] readIntArray( DataInput in ) throws IOException {
      int[] d = null;
      boolean isNotNull = in.readBoolean();
      if ( isNotNull ) {
         d = new int[in.readInt()];
         for ( int k = 0, length = d.length; k < length; k++ ) {
            d[k] = in.readInt();
         }
      }
      return d;
   }

   private void readListOfExternalizables( ObjectInput in, FieldAccessor f ) throws Exception {
      List d = null;
      boolean isNotNull = in.readBoolean();
      if ( isNotNull ) {
         boolean isArrayList = in.readBoolean();
         int size = in.readInt();
         if ( isArrayList ) {
            d = new ArrayList(size);
         } else {
            // TODO [MKR 12.10.2008] improvable by caching, but beware of threading issues (link to in?) and unneccessary loss of performance with hash lookups
            String className = in.readUTF();
            Class c = forName(className);
            d = (List)c.newInstance();
         }
         String defaultContentClassName = in.readUTF();
         Class<? extends Externalizable> externalizableClass = forName(defaultContentClassName);

         Class lastNonDefaultClass = null;
         for ( int k = 0; k < size; k++ ) {
            Externalizable instance = null;
            isNotNull = in.readBoolean();
            if ( isNotNull ) {
               boolean isDefaultType = in.readBoolean();
               if ( isDefaultType ) {
                  instance = externalizableClass.newInstance();
               } else {
                  boolean isSameAsLastNonDefault = in.readBoolean();
                  Class c;
                  if ( isSameAsLastNonDefault ) {
                     c = lastNonDefaultClass;
                  } else {
                     c = forName(in.readUTF());
                     lastNonDefaultClass = c;
                  }
                  instance = (Externalizable)c.newInstance();
               }
               instance.readExternal(in);
            }
            d.add(instance);
         }
      }
      if ( f != null ) {
         f.set(this, d);
      }
   }

   private void readListOfStrings( ObjectInput in, FieldAccessor f ) throws Exception {
      List d = null;
      boolean isNotNull = in.readBoolean();
      if ( isNotNull ) {
         boolean isArrayList = in.readBoolean();
         int size = in.readInt();
         if ( isArrayList ) {
            d = new ArrayList(size);
         } else {
            // TODO [MKR 12.10.2008] improvable by caching, but beware of threading issues (link to in?) and unneccessary loss of performance with hash lookups
            String className = in.readUTF();
            Class c = forName(className);
            d = (List)c.newInstance();
         }

         for ( int k = 0; k < size; k++ ) {
            String s = null;
            isNotNull = in.readBoolean();
            if ( isNotNull ) {
               s = in.readUTF();
            }
            d.add(s);
         }
      }
      if ( f != null ) {
         f.set(this, d);
      }
   }

   private long[] readLongArray( DataInput in ) throws IOException {
      long[] d = null;
      boolean isNotNull = in.readBoolean();
      if ( isNotNull ) {
         d = new long[in.readInt()];
         for ( int k = 0, length = d.length; k < length; k++ ) {
            d[k] = in.readLong();
         }
      }
      return d;
   }

   private String readString( ObjectInput in ) throws IOException {
      String s = null;
      boolean isNotNull = in.readBoolean();
      if ( isNotNull ) {
         s = in.readUTF();
      }
      return s;
   }

   private String[] readStringArray( ObjectInput in ) throws IOException {
      String[] d = null;
      boolean isNotNull = in.readBoolean();
      if ( isNotNull ) {
         d = new String[in.readInt()];
         for ( int k = 0, length = d.length; k < length; k++ ) {
            d[k] = readString(in);
         }
      }
      return d;
   }

   private UUID readUUID( ObjectInput in ) throws IOException {
      UUID uuid = null;
      boolean isNotNull = in.readBoolean();
      if ( isNotNull ) {
         long mostSignificantBits = in.readLong();
         long leastSignificantBits = in.readLong();
         uuid = new UUID(mostSignificantBits, leastSignificantBits);
      }
      return uuid;
   }

   private void writeByteArray( byte[] d, ObjectOutput out ) throws IOException {
      out.writeBoolean(d != null);
      if ( d != null ) {
         out.writeInt(d.length);
         for ( int j = 0, llength = d.length; j < llength; j++ ) {
            out.writeByte(d[j]);
         }
      }
   }

   private void writeDate( ObjectOutput out, Date s ) throws IOException {
      out.writeBoolean(s != null);
      if ( s != null ) {
         out.writeLong(s.getTime());
      }
   }

   private void writeDateArray( Date[] d, ObjectOutput out ) throws IOException {
      out.writeBoolean(d != null);
      if ( d != null ) {
         out.writeInt(d.length);
         for ( int j = 0, llength = d.length; j < llength; j++ ) {
            writeDate(out, d[j]);
         }
      }
   }

   private void writeDoubleArray( double[] d, ObjectOutput out ) throws IOException {
      out.writeBoolean(d != null);
      if ( d != null ) {
         out.writeInt(d.length);
         for ( int j = 0, llength = d.length; j < llength; j++ ) {
            out.writeDouble(d[j]);
         }
      }
   }

   private void writeExternalizableArray( ObjectOutput out, Externalizable[] d, Class defaultType ) throws Exception, IOException {
      out.writeBoolean(d != null);
      if ( d != null ) {
         out.writeInt(d.length);

         Class lastNonDefaultClass = null;
         for ( int j = 0, llength = d.length; j < llength; j++ ) {
            Externalizable instance = d[j];
            out.writeBoolean(instance != null);
            if ( instance != null ) {
               Class c = instance.getClass();
               boolean isDefaultType = c.equals(defaultType);
               out.writeBoolean(isDefaultType);
               if ( !isDefaultType ) {
                  boolean isSameAsLastNonDefault = c.equals(lastNonDefaultClass);
                  out.writeBoolean(isSameAsLastNonDefault);
                  if ( !isSameAsLastNonDefault ) {
                     out.writeUTF(c.getName());
                     lastNonDefaultClass = c;
                  }
               }
               instance.writeExternal(out);
            }
         }
      }

   }

   private void writeFloatArray( float[] d, ObjectOutput out ) throws IOException {
      out.writeBoolean(d != null);
      if ( d != null ) {
         out.writeInt(d.length);
         for ( int j = 0, llength = d.length; j < llength; j++ ) {
            out.writeFloat(d[j]);
         }
      }
   }

   private void writeIntArray( int[] d, ObjectOutput out ) throws IOException {
      out.writeBoolean(d != null);
      if ( d != null ) {
         out.writeInt(d.length);
         for ( int j = 0, llength = d.length; j < llength; j++ ) {
            out.writeInt(d[j]);
         }
      }
   }

   private void writeListOfExternalizables( ObjectOutput out, FieldAccessor f ) throws Exception, IOException {
      // TODO use defaultType
      // TODO add generic0DefaultType and use it here

      List d = (List)f.get(this);
      out.writeBoolean(d != null);
      if ( d != null ) {
         Class listClass = d.getClass();
         boolean isArrayList = listClass.equals(ArrayList.class);
         out.writeBoolean(isArrayList);
         out.writeInt(d.size());
         if ( !isArrayList ) {
            out.writeUTF(listClass.getName());
         }
         Class defaultType = f.getGenericTypes()[0];
         out.writeUTF(defaultType.getName());

         Class lastNonDefaultClass = null;
         for ( int j = 0, llength = d.size(); j < llength; j++ ) {
            Externalizable instance = (Externalizable)d.get(j);
            out.writeBoolean(instance != null);
            if ( instance != null ) {
               Class c = instance.getClass();
               boolean isDefaultType = c.equals(defaultType);
               out.writeBoolean(isDefaultType);
               if ( !isDefaultType ) {
                  boolean isSameAsLastNonDefault = c.equals(lastNonDefaultClass);
                  out.writeBoolean(isSameAsLastNonDefault);
                  if ( !isSameAsLastNonDefault ) {
                     out.writeUTF(c.getName());
                     lastNonDefaultClass = c;
                  }
               }
               instance.writeExternal(out);
            }
         }
      }
   }

   private void writeListOfStrings( ObjectOutput out, FieldAccessor f ) throws Exception, IOException {
      // TODO use defaultType, set to ArrayList, if List
      List d = (List)f.get(this);
      out.writeBoolean(d != null);
      if ( d != null ) {
         Class listClass = d.getClass();
         boolean isArrayList = listClass.equals(ArrayList.class);
         out.writeBoolean(isArrayList);
         out.writeInt(d.size());
         if ( !isArrayList ) {
            out.writeUTF(listClass.getName());
         }
         for ( int j = 0, llength = d.size(); j < llength; j++ ) {
            String s = (String)d.get(j);
            out.writeBoolean(s != null);
            if ( s != null ) {
               out.writeUTF(s);
            }
         }
      }
   }

   private void writeLongArray( long[] d, ObjectOutput out ) throws IOException {
      out.writeBoolean(d != null);
      if ( d != null ) {
         out.writeInt(d.length);
         for ( int j = 0, llength = d.length; j < llength; j++ ) {
            out.writeLong(d[j]);
         }
      }
   }

   private void writeString( ObjectOutput out, String s ) throws IOException {
      out.writeBoolean(s != null);
      if ( s != null ) {
         out.writeUTF(s);
      }
   }

   private void writeStringArray( String[] d, ObjectOutput out ) throws IOException {
      out.writeBoolean(d != null);
      if ( d != null ) {
         out.writeInt(d.length);
         for ( int j = 0, llength = d.length; j < llength; j++ ) {
            writeString(out, d[j]);
         }
      }
   }

   private void writeUUID( ObjectOutput out, UUID uuid ) throws IOException {
      out.writeBoolean(uuid != null);
      if ( uuid != null ) {
         out.writeLong(uuid.getMostSignificantBits());
         out.writeLong(uuid.getLeastSignificantBits());
      }
   }


   /**
    * It's enough to annotate either the getter or the setter. Of course you can also annotate both, but the indexes must match.
    */
   @Retention(RetentionPolicy.RUNTIME)
   @Target({ ElementType.FIELD, ElementType.METHOD })
   public @interface externalize {

      public byte value();
   }

   public enum FieldType {
      pInt(int.class, 0), //
      pBoolean(boolean.class, 1), //
      pByte(byte.class, 2), //
      pChar(char.class, 3), //
      pDouble(double.class, 4), //
      pFloat(float.class, 5), //
      pLong(long.class, 6), //
      pShort(short.class, 7), //
      String(String.class, 8), //
      Date(Date.class, 9), //
      Integer(Integer.class, 10), //
      Boolean(Boolean.class, 11), //
      Byte(Byte.class, 12), //
      Character(Character.class, 13), //
      Double(Double.class, 14), //
      Float(Float.class, 15), //
      Long(Long.class, 16), //
      Short(Short.class, 17), //
      Externalizable(Externalizable.class, 18, true), //
      StringArray(String[].class, 19), //
      DateArray(Date[].class, 20), //
      pIntArray(int[].class, 21), //
      pByteArray(byte[].class, 22), //
      pDoubleArray(double[].class, 23), //
      pFloatArray(float[].class, 24), //
      pLongArray(long[].class, 25), //
      ListOfExternalizables(List.class, 26), //
      ExternalizableArray(Externalizable[].class, 27, true), //
      ExternalizableArrayArray(Externalizable[][].class, 28, true), //
      Object(Object.class, 29), //
      UUID(UUID.class, 30), //
      StringArrayArray(String[][].class, 31), //
      DateArrayArray(Date[][].class, 32), //
      pIntArrayArray(int[][].class, 33), //
      pByteArrayArray(byte[][].class, 34), //
      pDoubleArrayArray(double[][].class, 35), //
      pFloatArrayArray(float[][].class, 36), //
      pLongArrayArray(long[][].class, 37), //      
      Enum(Enum.class, 38), // 
      EnumSet(EnumSet.class, 39), //
      ListOfStrings(System.class, 40), // System is just a placeholder - this FieldType is handled specially 
      // TODO add Set, Map (beware of Treemaps & -sets using custom Comparators!)
      ;

      private static final Map<Class, FieldType> _classLookup = new HashMap<Class, FieldType>(FieldType.values().length);
      private static final FieldType[]           _idLookup    = new FieldType[127];
      static {
         for ( FieldType ft : FieldType.values() ) {
            if ( _classLookup.get(ft._class) != null ) {
               throw new Error("Implementation mistake: FieldType._class must be unique! " + ft._class);
            }
            _classLookup.put(ft._class, ft);
            if ( _idLookup[ft._id] != null ) {
               throw new Error("Implementation mistake: FieldType._id must be unique! " + ft._id);
            }
            _idLookup[ft._id] = ft;
         }
      }


      public static final FieldType forClass( Class c ) {
         return _classLookup.get(c);
      }

      public static final FieldType forId( byte id ) {
         return _idLookup[id];
      }


      private final Class _class;
      private final byte  _id;
      private boolean     _lengthDynamic = false;


      private FieldType( Class c, int id ) {
         _class = c;
         _id = (byte)id;
      }

      private FieldType( Class c, int id, boolean lengthDynamic ) {
         this(c, id);
         _lengthDynamic = lengthDynamic;
      }

      public boolean isLengthDynamic() {
         return _lengthDynamic;
      }
   }

   private static class BytesCache extends OutputStream {

      // this is basically an unsynchronized ByteArrayOutputStream with a writeTo(ObjectOutput) method

      protected byte[] _buffer;
      protected int    _count;


      public BytesCache() {
         this(1024);
      }

      public BytesCache( int size ) {
         if ( size < 0 ) {
            throw new IllegalArgumentException("Negative initial size: " + size);
         }
         this._buffer = new byte[size];
      }

      public void reset() {
         _count = 0;
         if ( _buffer.length > 1048576 ) {
            // let it shrink
            _buffer = new byte[1024];
         }
      }

      public int size() {
         return _count;
      }

      @Override
      public void write( byte[] bytes, int start, int length ) {
         if ( (start < 0) || (start > bytes.length) || (length < 0) || (start + length > bytes.length) || (start + length < 0) ) {
            throw new IndexOutOfBoundsException();
         }
         if ( length == 0 ) {
            return;
         }
         int i = _count + length;
         if ( i > _buffer.length ) {
            _buffer = Arrays.copyOf(_buffer, Math.max(_buffer.length << 1, i));
         }
         System.arraycopy(bytes, start, _buffer, _count, length);
         this._count = i;
      }

      @Override
      public void write( int data ) {
         int i = this._count + 1;
         if ( i > this._buffer.length ) {
            this._buffer = Arrays.copyOf(_buffer, Math.max(_buffer.length << 1, i));
         }
         _buffer[_count] = (byte)data;
         _count = i;
      }

      public void writeTo( ObjectOutput out ) throws IOException {
         out.write(_buffer, 0, _count);
      }
   }

   private static class ClassConfig {

      Class           _class;
      ClassLoader     _classLoader;
      FieldAccessor[] _fieldAccessors;
      byte[]          _fieldIndexes;
      FieldType[]     _fieldTypes;
      Class[]         _defaultTypes;


      public ClassConfig( Class clientClass ) {
         try {
            clientClass.getConstructor();
         }
         catch ( NoSuchMethodException argh ) {
            throw new RuntimeException(clientClass + " extends Externalizer, but does not have a public nullary constructor.");
         }

         _class = clientClass;
         _classLoader = clientClass.getClassLoader();
         List<FieldInfo> fieldInfos = new ArrayList<FieldInfo>();

         initFromFields(fieldInfos);

         initFromMethods(fieldInfos);

         Collections.sort(fieldInfos);

         _fieldAccessors = new FieldAccessor[fieldInfos.size()];
         _fieldIndexes = new byte[fieldInfos.size()];
         _fieldTypes = new FieldType[fieldInfos.size()];
         _defaultTypes = new Class[fieldInfos.size()];
         for ( int i = 0, length = fieldInfos.size(); i < length; i++ ) {
            FieldInfo fi = fieldInfos.get(i);
            _fieldAccessors[i] = fi._fieldAccessor;
            _fieldIndexes[i] = fi._fieldIndex;
            _fieldTypes[i] = fi._fieldType;
            _defaultTypes[i] = fi._defaultType;
         }
         if ( _fieldAccessors.length == 0 ) {
            throw new RuntimeException(_class + " extends Externalizer, but it has no externalizable fields or methods. "
               + "This is most probably a bug. Externalizable fields and methods must be public.");
         }
      }

      private void addFieldInfo( List<FieldInfo> fieldInfos, externalize annotation, FieldAccessor fieldAccessor, Class type, String fieldName ) {
         FieldInfo fi = new FieldInfo();
         fi._fieldAccessor = fieldAccessor;
         fi._fieldType = getFieldType(fi, type);
         fi.setDefaultType(type, fi._fieldType);

         byte index = annotation.value();
         for ( FieldInfo ffi : fieldInfos ) {
            if ( ffi._fieldIndex == index ) {
               throw new RuntimeException(_class + " extends Externalizer, but " + fieldName + " has a non-unique index " + index);
            }
         }
         fi._fieldIndex = index;
         fieldInfos.add(fi);
      }

      private FieldType getFieldType( FieldInfo fi, Class type ) {
         FieldType ft = FieldType.forClass(type);
         if ( ft == null ) {
            if ( Externalizable.class.isAssignableFrom(type) ) {
               ft = FieldType.Externalizable;
            }
         }
         if ( ft == null ) {
            Class arrayType = type.getComponentType();
            if ( arrayType != null && Externalizable.class.isAssignableFrom(arrayType) ) {
               ft = FieldType.ExternalizableArray;
            }
         }
         if ( ft == null ) {
            Class arrayType = type.getComponentType();
            if ( arrayType != null ) {
               arrayType = arrayType.getComponentType();
               if ( arrayType != null && Externalizable.class.isAssignableFrom(arrayType) ) {
                  ft = FieldType.ExternalizableArrayArray;
               }
            }
         }
         if ( ft == null ) {
            if ( Enum.class.isAssignableFrom(type) ) {
               ft = FieldType.Enum;
            }
         }
         if ( ft == null ) {
            if ( EnumSet.class.isAssignableFrom(type) ) {
               ft = FieldType.EnumSet;
            }
         }
         if ( ft == null ) {
            ft = FieldType.Object;
            //               throw new RuntimeException(_class + " extends Externalizer, but the member variable " + f.getName() + " has an unsupported type: " + type);
         }
         if ( ft == FieldType.ListOfExternalizables
            && (fi._fieldAccessor.getGenericTypes().length != 1 || !Externalizable.class.isAssignableFrom(fi._fieldAccessor.getGenericTypes()[0])) ) {
            if ( fi._fieldAccessor.getGenericTypes().length == 1 && String.class == fi._fieldAccessor.getGenericTypes()[0] ) {
               ft = FieldType.ListOfStrings;
            } else {
               ft = FieldType.Object;
               //               throw new RuntimeException(_class + " extends Externalizer, but the member variable " + f.getName()
               //                  + " has a generic List with an unsupported type: " + type + " - the generic type of a list must be Externalizable");
            }
         }

         return ft;
      }

      private void initFromFields( List<FieldInfo> fieldInfos ) {
         Class c = _class;
         while ( c != Object.class ) {
            for ( Field f : c.getDeclaredFields() ) {
               int mod = f.getModifiers();
               if ( Modifier.isFinal(mod) || Modifier.isStatic(mod) ) {
                  continue;
               }
               externalize annotation = f.getAnnotation(externalize.class);
               if ( annotation == null ) {
                  continue;
               }

               if ( !Modifier.isPublic(mod) ) {
                  f.setAccessible(true); // enable access to the field - ...hackity hack
               }

               FieldFieldAccessor fieldAccessor = USE_UNSAFE_FIELD_ACCESSORS ? new UnsafeFieldFieldAccessor(f) : new FieldFieldAccessor(f);
               Class type = f.getType();
               addFieldInfo(fieldInfos, annotation, fieldAccessor, type, f.getName());
            }
            c = c.getSuperclass();
         }
      }

      private void initFromMethods( List<FieldInfo> fieldInfos ) {
         methodLoop: for ( Method m : _class.getMethods() ) {
            int mod = m.getModifiers();
            if ( Modifier.isStatic(mod) ) {
               continue;
            }

            Method getter = null, setter = null;
            if ( m.getName().startsWith("get") || (m.getName().startsWith("is") && m.getReturnType() == boolean.class) ) {
               getter = m;
            } else if ( m.getName().startsWith("set") ) {
               setter = m;
            } else {
               continue;
            }

            if ( getter != null ) {
               for ( FieldInfo ffi : fieldInfos ) {
                  if ( ffi._fieldAccessor instanceof MethodFieldAccessor ) {
                     MethodFieldAccessor mfa = (MethodFieldAccessor)ffi._fieldAccessor;
                     if ( mfa.getGetter().getName().equals(getter.getName()) ) {
                        continue methodLoop;
                     }
                  }
               }

               Class type = getter.getReturnType();
               if ( getter.getParameterTypes().length > 0 ) {
                  externalize getterAnnotation = getter.getAnnotation(externalize.class);
                  if ( getterAnnotation != null ) {
                     throw new RuntimeException(_class + " extends Externalizer, but the annotated getter method " + getter.getName() + " has a parameter.");
                  } else {
                     continue;
                  }
               }

               try {
                  String name = getter.getName();
                  name = getter.getName().startsWith("is") ? name.substring(2) : name.substring(3);
                  setter = _class.getMethod("set" + name, type);
               }
               catch ( NoSuchMethodException e ) {
                  externalize getterAnnotation = getter.getAnnotation(externalize.class);
                  if ( getterAnnotation != null ) {
                     throw new RuntimeException(_class + " extends Externalizer, but the annotated getter method " + getter.getName()
                        + " has no appropriate setter with the correct parameter.");
                  } else {
                     continue;
                  }
               }
            } else if ( setter != null ) {
               for ( FieldInfo ffi : fieldInfos ) {
                  if ( ffi._fieldAccessor instanceof MethodFieldAccessor ) {
                     MethodFieldAccessor mfa = (MethodFieldAccessor)ffi._fieldAccessor;
                     if ( mfa.getSetter().getName().equals(setter.getName()) ) {
                        continue methodLoop;
                     }
                  }
               }

               if ( setter.getParameterTypes().length != 1 ) {
                  externalize setterAnnotation = setter.getAnnotation(externalize.class);
                  if ( setterAnnotation != null ) {
                     throw new RuntimeException(_class + " extends Externalizer, but the annotated setter method " + setter.getName()
                        + " does not have a single parameter.");
                  } else {
                     continue;
                  }
               }
               Class type = setter.getParameterTypes()[0];

               try {
                  getter = _class.getMethod("get" + setter.getName().substring(3));
               }
               catch ( NoSuchMethodException e ) {
                  externalize setterAnnotation = setter.getAnnotation(externalize.class);
                  if ( setterAnnotation != null ) {
                     throw new RuntimeException(_class + " extends Externalizer, but the annotated setter method " + setter.getName()
                        + " has no appropriate getter.");
                  } else {
                     continue;
                  }
               }

               if ( getter.getReturnType() != type ) {
                  externalize setterAnnotation = setter.getAnnotation(externalize.class);
                  externalize getterAnnotation = getter.getAnnotation(externalize.class);
                  if ( getterAnnotation != null || setterAnnotation != null ) {
                     throw new RuntimeException(_class + " extends Externalizer, but the annotated setter method " + setter.getName()
                        + " has no getter with the correct return type.");
                  } else {
                     continue;
                  }
               }
            }

            assert getter != null;
            assert setter != null;

            externalize getterAnnotation = getter.getAnnotation(externalize.class);
            externalize setterAnnotation = setter.getAnnotation(externalize.class);
            if ( getterAnnotation == null && setterAnnotation == null ) {
               continue;
            }
            if ( getterAnnotation != null && setterAnnotation != null && getterAnnotation.value() != setterAnnotation.value() ) {
               throw new RuntimeException(_class + " extends Externalizer, but the getter/setter pair " + getter.getName()
                  + " has different indexes in the externalize annotations.");
            }
            externalize annotation = getterAnnotation == null ? setterAnnotation : getterAnnotation;

            FieldAccessor fieldAccessor = new MethodFieldAccessor(getter, setter);
            Class type = getter.getReturnType();
            addFieldInfo(fieldInfos, annotation, fieldAccessor, type, getter.getName());
         }
      }
   }

   private static class FieldInfo implements Comparable<FieldInfo> {

      FieldAccessor _fieldAccessor;
      FieldType     _fieldType;
      byte          _fieldIndex;
      Class         _defaultType;


      public int compareTo( FieldInfo o ) {
         return (_fieldIndex < o._fieldIndex ? -1 : (_fieldIndex == o._fieldIndex ? 0 : 1));
      }

      private void setDefaultType( Class type, FieldType ft ) {
         _defaultType = type;
         if ( ft == FieldType.ExternalizableArray ) {
            _defaultType = type.getComponentType();
         } else if ( ft == FieldType.ExternalizableArrayArray ) {
            _defaultType = type.getComponentType().getComponentType();
         }
      }
   }

}
