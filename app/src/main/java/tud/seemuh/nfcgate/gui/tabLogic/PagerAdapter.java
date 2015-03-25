package tud.seemuh.nfcgate.gui.tabLogic;

import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import tud.seemuh.nfcgate.gui.fragments.CloneFragment;
import tud.seemuh.nfcgate.gui.fragments.RelayFragment;

public class PagerAdapter extends FragmentPagerAdapter {
    private Fragment frag_ref;

    public PagerAdapter(FragmentManager fm) {
        super(fm);
    }
    /**
     * @return the number of pages to display
     */
    @Override
    public int getCount() {
        return 2;
    }


    @Override
    public CharSequence getPageTitle(int position) {
        return "Item " + (position + 1);
    }


    @Override
    public Fragment getItem(int pos) {
        switch(pos) {

            case 0: return RelayFragment.newInstance();
            case 1: return CloneFragment.newInstance();
            default: return CloneFragment.newInstance();
        }
    }

    /**
     * Destroy the item from the {@link android.support.v4.view.ViewPager}. In our case this is simply removing the
     * {@link android.view.View}.
     */
    @Override
    public void destroyItem(ViewGroup container, int position, Object object) {
        container.removeView((View) object);
    }
}