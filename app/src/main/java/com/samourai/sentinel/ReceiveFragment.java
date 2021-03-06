package com.samourai.sentinel;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
//import android.util.Log;

import com.google.bitcoin.uri.BitcoinURI;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.android.Contents;
import com.google.zxing.client.android.encode.QRCodeEncoder;
import com.samourai.sentinel.api.APIFactory;
import com.samourai.sentinel.util.AddressFactory;
import com.samourai.sentinel.util.AppUtil;
import com.samourai.sentinel.util.ExchangeRateFactory;
import com.samourai.sentinel.util.MonetaryUtil;
import com.samourai.sentinel.util.PrefsUtil;

import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

//import com.google.bitcoin.core.Coin;

public class ReceiveFragment extends Fragment {

    private static final String ARG_SECTION_NUMBER = "section_number";

    private Locale locale = null;

    private static Display display = null;
    private static int imgWidth = 0;

    private ImageView ivQR = null;
    private TextView tvAddress = null;
    private LinearLayout addressLayout = null;

    private EditText edAmountBTC = null;
    private EditText edAmountFiat = null;
    private TextWatcher textWatcherBTC = null;
    private TextWatcher textWatcherFiat = null;

    private String defaultSeparator = null;

    private String strFiat = null;
    private double btc_fx = 286.0;
    private TextView tvFiatSymbol = null;

    private String addr = null;

    private boolean canRefresh = false;
    private Menu _menu = null;

    public static ReceiveFragment newInstance(int sectionNumber) {
        ReceiveFragment fragment = new ReceiveFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_SECTION_NUMBER, sectionNumber);
        fragment.setArguments(args);
        return fragment;
    }

    public ReceiveFragment() {
        ;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = null;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            rootView = inflater.inflate(R.layout.fragment_receive, container, false);
        }
        else {
            rootView = inflater.inflate(R.layout.fragment_receive_compat, container, false);
        }

        rootView.setFilterTouchesWhenObscured(true);

        rootView.setOnTouchListener(new View.OnTouchListener()
        {
            @Override
            public boolean onTouch(View v, MotionEvent event)
            {
                return true;
            }
        });

        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            rootView.setBackgroundColor(getActivity().getResources().getColor(R.color.divider));
        }

        locale = Locale.getDefault();

//        getActivity().getActionBar().setTitle("My Bitcoin Address");
        setHasOptionsMenu(true);

        getActivity().getActionBar().setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);

        display = (getActivity()).getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        imgWidth = size.x - 240;

        addr = getReceiveAddress();

        addressLayout = (LinearLayout)rootView.findViewById(R.id.receive_address_layout);
        addressLayout.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {

                new AlertDialog.Builder(getActivity())
                        .setTitle(R.string.app_name)
                        .setMessage(R.string.receive_address_to_clipboard)
                        .setCancelable(false)
                        .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {

                            public void onClick(DialogInterface dialog, int whichButton) {
                                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getActivity().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                                android.content.ClipData clip = null;
                                clip = android.content.ClipData.newPlainText("Receive address", addr);
                                clipboard.setPrimaryClip(clip);
                                Toast.makeText(getActivity(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
                            }

                        }).setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {

                    public void onClick(DialogInterface dialog, int whichButton) {
                        ;
                    }
                }).show();

                return false;
            }
        });

        tvAddress = (TextView)rootView.findViewById(R.id.receive_address);

        ivQR = (ImageView)rootView.findViewById(R.id.qr);
        ivQR.setMaxWidth(imgWidth);

        ivQR.setOnTouchListener(new OnSwipeTouchListener(getActivity()) {
            @Override
            public void onSwipeLeft() {
                if(canRefresh) {
                    addr = getReceiveAddress();
                    canRefresh = false;
                    displayQRCode();
                }
            }
        });

        DecimalFormat format = (DecimalFormat) DecimalFormat.getInstance(Locale.getDefault());
        DecimalFormatSymbols symbols=format.getDecimalFormatSymbols();
        defaultSeparator = Character.toString(symbols.getDecimalSeparator());

        strFiat = PrefsUtil.getInstance(getActivity()).getValue(PrefsUtil.CURRENT_FIAT, "USD");
        btc_fx = ExchangeRateFactory.getInstance(getActivity()).getAvgPrice(strFiat);
        tvFiatSymbol = (TextView)rootView.findViewById(R.id.fiatSymbol);
        tvFiatSymbol.setText(getDisplayUnits() + "-" + strFiat);

        edAmountBTC = (EditText)rootView.findViewById(R.id.amountBTC);
        edAmountFiat = (EditText)rootView.findViewById(R.id.amountFiat);

        textWatcherBTC = new TextWatcher() {

            public void afterTextChanged(Editable s) {

                edAmountBTC.removeTextChangedListener(this);
                edAmountFiat.removeTextChangedListener(textWatcherFiat);

                int unit = PrefsUtil.getInstance(getActivity()).getValue(PrefsUtil.BTC_UNITS, MonetaryUtil.UNIT_BTC);
                int max_len = 8;
                NumberFormat btcFormat = NumberFormat.getInstance(Locale.getDefault());
                switch (unit) {
                    case MonetaryUtil.MICRO_BTC:
                        max_len = 2;
                        break;
                    case MonetaryUtil.MILLI_BTC:
                        max_len = 4;
                        break;
                    default:
                        max_len = 8;
                        break;
                }
                btcFormat.setMaximumFractionDigits(max_len + 1);
                btcFormat.setMinimumFractionDigits(0);

                double d = 0.0;
                try {
                    d = Double.parseDouble(s.toString());
                    String s1 = btcFormat.format(d);
                    if (s1.indexOf(defaultSeparator) != -1) {
                        String dec = s1.substring(s1.indexOf(defaultSeparator));
                        if (dec.length() > 0) {
                            dec = dec.substring(1);
                            if (dec.length() > max_len) {
                                edAmountBTC.setText(s1.substring(0, s1.length() - 1));
                                edAmountBTC.setSelection(edAmountBTC.getText().length());
                                s = edAmountBTC.getEditableText();
                            }
                        }
                    }
                } catch (NumberFormatException nfe) {
                    ;
                }

                switch (unit) {
                    case MonetaryUtil.MICRO_BTC:
                        d = d / 1000000.0;
                        break;
                    case MonetaryUtil.MILLI_BTC:
                        d = d / 1000.0;
                        break;
                    default:
                        break;
                }

                edAmountFiat.setText(MonetaryUtil.getInstance().getFiatFormat(strFiat).format(d * btc_fx));
                edAmountFiat.setSelection(edAmountFiat.getText().length());

                edAmountFiat.addTextChangedListener(textWatcherFiat);
                edAmountBTC.addTextChangedListener(this);

                displayQRCode();
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                ;
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
                ;
            }
        };
        edAmountBTC.addTextChangedListener(textWatcherBTC);

        textWatcherFiat = new TextWatcher() {

            public void afterTextChanged(Editable s) {

                edAmountFiat.removeTextChangedListener(this);
                edAmountBTC.removeTextChangedListener(textWatcherBTC);

                int max_len = 2;
                NumberFormat fiatFormat = NumberFormat.getInstance(Locale.getDefault());
                fiatFormat.setMaximumFractionDigits(max_len + 1);
                fiatFormat.setMinimumFractionDigits(0);

                double d = 0.0;
                try	{
                    d = Double.parseDouble(s.toString());
                    String s1 = fiatFormat.format(d);
                    if(s1.indexOf(defaultSeparator) != -1)	{
                        String dec = s1.substring(s1.indexOf(defaultSeparator));
                        if(dec.length() > 0)	{
                            dec = dec.substring(1);
                            if(dec.length() > max_len)	{
                                edAmountFiat.setText(s1.substring(0, s1.length() - 1));
                                edAmountFiat.setSelection(edAmountFiat.getText().length());
                            }
                        }
                    }
                }
                catch(NumberFormatException nfe)	{
                    ;
                }

                int unit = PrefsUtil.getInstance(getActivity()).getValue(PrefsUtil.BTC_UNITS, MonetaryUtil.UNIT_BTC);
                switch (unit) {
                    case MonetaryUtil.MICRO_BTC:
                        d = d * 1000000.0;
                        break;
                    case MonetaryUtil.MILLI_BTC:
                        d = d * 1000.0;
                        break;
                    default:
                        break;
                }

                edAmountBTC.setText(MonetaryUtil.getInstance().getBTCFormat().format(d / btc_fx));
                edAmountBTC.setSelection(edAmountBTC.getText().length());

                edAmountBTC.addTextChangedListener(textWatcherBTC);
                edAmountFiat.addTextChangedListener(this);

                displayQRCode();
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                ;
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
                ;
            }
        };
        edAmountFiat.addTextChangedListener(textWatcherFiat);

        displayQRCode();

        return rootView;
    }

    @Override
    public void onDestroy() {
        getActivity().getActionBar().setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
        super.onDestroy();
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

//        menu.findItem(R.id.action_sweep).setVisible(false);
        menu.findItem(R.id.action_settings).setVisible(false);

        MenuItem itemShare = menu.findItem(R.id.action_share_receive).setVisible(true);
        itemShare.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {

                new AlertDialog.Builder(getActivity())
                        .setTitle(R.string.app_name)
                        .setMessage(R.string.receive_address_to_share)
                        .setCancelable(false)
                        .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {

                            public void onClick(DialogInterface dialog, int whichButton) {

                                String strFileName = AppUtil.getInstance(getActivity()).getReceiveQRFilename();
                                File file = new File(strFileName);
                                if(!file.exists()) {
                                    try {
                                        file.createNewFile();
                                    }
                                    catch(Exception e) {
                                        Toast.makeText(getActivity(), e.getMessage(), Toast.LENGTH_SHORT).show();
                                    }
                                }
                                file.setReadable(true, false);

                                FileOutputStream fos = null;
                                try {
                                    fos = new FileOutputStream(file);
                                }
                                catch(FileNotFoundException fnfe) {
                                    ;
                                }

                                android.content.ClipboardManager clipboard = (android.content.ClipboardManager)getActivity().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                                android.content.ClipData clip = null;
                                clip = android.content.ClipData.newPlainText("Receive address", addr);
                                clipboard.setPrimaryClip(clip);

                                if(file != null && fos != null) {
                                    Bitmap bitmap = ((BitmapDrawable)ivQR.getDrawable()).getBitmap();
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 0, fos);

                                    try {
                                        fos.close();
                                    }
                                    catch(IOException ioe) {
                                        ;
                                    }

                                    Intent intent = new Intent();
                                    intent.setAction(Intent.ACTION_SEND);
                                    intent.setType("image/png");
                                    intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file));
                                    startActivity(Intent.createChooser(intent, getActivity().getText(R.string.send_payment_code)));
                                }

                            }

                        }).setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {

                    public void onClick(DialogInterface dialog, int whichButton) {
                        ;
                    }
                }).show();

                return false;
            }
        });

        MenuItem itemRefresh = menu.findItem(R.id.action_refresh).setVisible(false);
        itemRefresh.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {

                if (canRefresh) {
                    addr = getReceiveAddress();
                    canRefresh = false;
                    displayQRCode();
                }

                return false;
            }
        });

        _menu = menu;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        ((MainActivity2) activity).onSectionAttached(getArguments().getInt(ARG_SECTION_NUMBER));
    }

    private void displayQRCode() {

        BigInteger bamount = null;
        try {
            double amount = NumberFormat.getInstance(locale).parse(edAmountBTC.getText().toString()).doubleValue();

            int unit = PrefsUtil.getInstance(getActivity()).getValue(PrefsUtil.BTC_UNITS, MonetaryUtil.UNIT_BTC);
            switch (unit) {
                case MonetaryUtil.MICRO_BTC:
                    amount = amount / 1000000.0;
                    break;
                case MonetaryUtil.MILLI_BTC:
                    amount = amount / 1000.0;
                    break;
                default:
                    break;
            }

            long lamount = (long)(amount * 1e8);
            bamount = BigInteger.valueOf(lamount);
            if(!bamount.equals(BigInteger.ZERO)) {
                ivQR.setImageBitmap(generateQRCode(BitcoinURI.convertToBitcoinURI(addr, bamount, null, null)));
            }
            else {
                ivQR.setImageBitmap(generateQRCode(addr));
            }
        }
        catch(NumberFormatException nfe) {
            ivQR.setImageBitmap(generateQRCode(addr));
        }
        catch(ParseException pe) {
            ivQR.setImageBitmap(generateQRCode(addr));
        }

        tvAddress.setText(addr);
        checkPrevUse();
    }

    private Bitmap generateQRCode(String uri) {

        Bitmap bitmap = null;

        QRCodeEncoder qrCodeEncoder = new QRCodeEncoder(uri, null, Contents.Type.TEXT, BarcodeFormat.QR_CODE.toString(), imgWidth);

        try {
            bitmap = qrCodeEncoder.encodeAsBitmap();
        } catch (WriterException e) {
            e.printStackTrace();
        }

        return bitmap;
    }

    private void checkPrevUse() {

        final Set<String> xpubKeys = SamouraiSentinel.getInstance(getActivity()).getXPUBs().keySet();
        final Set<String> legacyKeys = SamouraiSentinel.getInstance(getActivity()).getLegacy().keySet();
        final List<String> xpubList = new ArrayList<String>();
        xpubList.addAll(xpubKeys);
        xpubList.addAll(legacyKeys);
        if(!xpubList.get(SamouraiSentinel.getInstance(getActivity()).getCurrentSelectedAccount() - 1).startsWith("xpub"))    {
            return;
        }

        final Handler handler = new Handler();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject jsonObject = APIFactory.getInstance(getActivity()).getAddressInfo(addr);

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if(jsonObject != null) {
                                    if(jsonObject.has("n_tx") && (jsonObject.getLong("n_tx") > 0)) {
                                        Toast.makeText(getActivity(), R.string.address_used_previously, Toast.LENGTH_SHORT).show();
                                        canRefresh = true;
                                        _menu.findItem(R.id.action_refresh).setVisible(true);
                                    }
                                    else {
                                        canRefresh = false;
                                        _menu.findItem(R.id.action_refresh).setVisible(false);
                                    }
                                }

                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    public String getDisplayUnits() {

        return (String) MonetaryUtil.getInstance().getBTCUnits()[PrefsUtil.getInstance(getActivity()).getValue(PrefsUtil.BTC_UNITS, MonetaryUtil.UNIT_BTC)];

    }

    private String getReceiveAddress()  {

        final Set<String> xpubKeys = SamouraiSentinel.getInstance(getActivity()).getXPUBs().keySet();
        final Set<String> legacyKeys = SamouraiSentinel.getInstance(getActivity()).getLegacy().keySet();
        final List<String> xpubList = new ArrayList<String>();
        xpubList.addAll(xpubKeys);
        xpubList.addAll(legacyKeys);

        String addr = null;

        if(xpubList.get(SamouraiSentinel.getInstance(getActivity()).getCurrentSelectedAccount() - 1).startsWith("xpub"))    {
            String xpub = xpubList.get(SamouraiSentinel.getInstance(getActivity()).getCurrentSelectedAccount() - 1);
            int account = AddressFactory.getInstance(getActivity()).xpub2account().get(xpub);
            addr = AddressFactory.getInstance(getActivity()).get(AddressFactory.RECEIVE_CHAIN, account);
        }
        else    {
            addr = xpubList.get(SamouraiSentinel.getInstance(getActivity()).getCurrentSelectedAccount() - 1);
        }

        return addr;

    }

}
